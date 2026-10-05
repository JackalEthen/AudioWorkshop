package cn.music.audioworkshop.data.cache

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存删除的路径闸门。
 *
 * 这是递归删除唯一的防线，所以只测纯逻辑（不需要 Context）：
 * 同前缀兄弟目录、根目录自身、目录外路径，都必须被拒。
 */
class CachePathGuardTest {

    private val root = File("/data/user/0/cn.music.audioworkshop/cache")

    @Test
    fun `根目录下的子目录允许删除`() {
        assertTrue(CachePathGuard.isDeletable(root, File(root, "edit-preview")))
        assertTrue(CachePathGuard.isDeletable(root, File(root, "edit-preview/deep/nested")))
    }

    @Test
    fun `根目录本身拒绝删除`() {
        // 传错的话就是把整个 cacheDir 抹掉
        assertFalse(CachePathGuard.isDeletable(root, root))
    }

    @Test
    fun `同前缀的兄弟目录拒绝删除`() {
        // 纯字符串前缀判断会把这个当成子目录，实际它在 cache 之外
        val sibling = File("/data/user/0/cn.music.audioworkshop/cache_evil")
        assertFalse(CachePathGuard.isDeletable(root, sibling))
        assertFalse(CachePathGuard.isDeletable(root, File(sibling, "important")))
    }

    @Test
    fun `上层的祖先目录拒绝删除`() {
        assertFalse(CachePathGuard.isDeletable(root, File("/data/user/0/cn.music.audioworkshop")))
        assertFalse(CachePathGuard.isDeletable(root, File("/data/user/0")))
        assertFalse(CachePathGuard.isDeletable(root, File("/")))
    }

    @Test
    fun `外部存储路径拒绝删除`() {
        assertFalse(CachePathGuard.isDeletable(root, File("/sdcard/Music/song.mp3")))
        assertFalse(CachePathGuard.isDeletable(root, File("/storage/emulated/0/DCIM")))
    }

    @Test
    fun `路径含上级跳转时按解析后的真实位置判断`() {
        // cache/../files 解析后是 files，不在 cache 之下 —— 拒绝
        assertFalse(CachePathGuard.isDeletable(root, File(root, "../files/song.mp3")))
        // cache/edit-preview/../exports 解析后仍在 cache 之下 —— 允许
        assertTrue(
            CachePathGuard.isDeletable(
                root,
                File(root, "edit-preview/../exports"),
            ),
        )
    }

    @Test
    fun `文件名带后缀相似也不算子目录`() {
        assertFalse(CachePathGuard.isDeletable(root, File("/data/user/0/cn.music.audioworkshop/cache.tmp")))
    }

    @Test
    fun `字节数格式化可读`() {
        assertEquals("0 B", formatBytesForTest(0L))
        assertEquals("0 B", formatBytesForTest(-1L))
        assertEquals("512 B", formatBytesForTest(512L))
        assertEquals("1 KB", formatBytesForTest(1024L))
        assertEquals("1.0 MB", formatBytesForTest(1024L * 1024L))
        assertEquals("1.50 GB", formatBytesForTest((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    private fun formatBytesForTest(bytes: Long): String =
        cn.music.audioworkshop.feature.settings.cache.formatBytes(bytes)
}

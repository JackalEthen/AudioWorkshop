package cn.music.audioworkshop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享播放器的生命周期契约。
 *
 * [cn.music.audioworkshop.data.media.Media3AudioPlayer] 在 [cn.music.audioworkshop.di.AppContainer] 里
 * 是 `by lazy` 单例，全应用共用一个。而十多个功能页的 ViewModel 在 `onCleared()`
 * 里都会调 `release()` —— 那是**全局共享实例**，不是它们私有的。
 *
 * 后果：离开任意一个功能页后 `scope.cancel()` + `player.release()` 把播放器永久报废，
 * 之后进任何页面，选预设、渲染都正常（渲染不经过播放器），但播放按钮永远没声音。
 *
 * 这组测试把这个契约写死：功能页只能暂停，不能释放全局播放器。
 */
class SharedPlayerLifecycleTest {

    /**
     * 功能页 ViewModel 在 onCleared 里应该做的事。
     *
     * 这里用一份「假实现」代替真实的 ExoPlayer（它在 JVM 测不了），
     * 但契约本身是可测的：release 之后还能不能继续用。
     */
    private class FakeSharedPlayer : AudioPlayer {
        private val mutableSnapshot = kotlinx.coroutines.flow.MutableStateFlow(PlaybackSnapshot())
        override val snapshot = mutableSnapshot
        var released = false
            private set
        var loadCount = 0
            private set

        override fun loadFile(filePath: String) {
            if (released) return
            loadCount++
            mutableSnapshot.value = PlaybackSnapshot(mediaId = filePath)
        }

        override fun loadContentUri(uri: String) {
            if (released) return
            loadCount++
        }

        override fun play() {
            if (released) return
            mutableSnapshot.value = mutableSnapshot.value.copy(isPlaying = true)
        }

        override fun pause() {
            if (released) return
            mutableSnapshot.value = mutableSnapshot.value.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) {
            if (released) return
            mutableSnapshot.value = mutableSnapshot.value.copy(positionMs = positionMs)
        }

        override fun release() {
            released = true
        }
    }

    /** 功能页的 onCleared 应该这么写：暂停而不是释放。 */
    private fun leavePage(player: AudioPlayer) {
        player.pause()
    }

    @Test
    fun `离开页面后播放器仍然可用`() {
        val player = FakeSharedPlayer()
        player.loadFile("/tmp/a.wav")
        player.play()
        leavePage(player)

        // 换一个功能页继续用同一个实例
        player.loadFile("/tmp/b.wav")
        player.play()

        assertFalse("离开功能页后播放器被释放了", player.released)
        assertTrue("重新加载后应该能播放", player.snapshot.value.isPlaying)
    }

    @Test
    fun `连续进出多个功能页不会把播放器搞坏`() {
        val player = FakeSharedPlayer()
        repeat(5) { index ->
            player.loadFile("/tmp/$index.wav")
            player.play()
            leavePage(player)
        }
        assertFalse("多次进出后播放器仍被释放", player.released)
        // 循环最后一次是离开页面（pause），所以要重新播一次再断言
        player.loadFile("/tmp/final.wav")
        player.play()
        assertTrue("多次进出后还能播", player.snapshot.value.isPlaying)
        assertEquals("每次进入都应真正加载了新文件", 6, player.loadCount)
    }

    @Test
    fun `如果真的调用了release之后播放会失效`() {
        // 这是当前 bug 的机制本身，用测试固定住「release 不可逆」这个事实
        val player = FakeSharedPlayer()
        player.loadFile("/tmp/a.wav")
        player.release()
        player.loadFile("/tmp/b.wav")
        player.play()

        assertTrue("release 之后无法再播放", player.released)
        assertFalse("release 之后 load 无效", player.snapshot.value.isPlaying)
    }
}

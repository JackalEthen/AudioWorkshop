package cn.qishui.tool.feature.player

import cn.qishui.tool.domain.source.LxSourceInfo
import cn.qishui.tool.domain.source.LxSourceState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索平台交集逻辑。
 *
 * 只看一边都会给出「点得动但必然失败」的选项：
 * - 只按我们实现了搜索来 → 选到音源不支持的平台，点了必报解析失败
 * - 只按音源能力来   → 出现 `local`，而本地文件根本不该出现在搜索标签里
 */
class SearchablePlatformsTest {

    private fun info(vararg platforms: String) = LxSourceInfo(
        name = "测试源",
        description = "",
        author = "",
        version = "",
        homepage = "",
        qualitys = platforms.associateWith { listOf("128k") },
        actions = platforms.associateWith { listOf(LxSourceInfo.ACTION_MUSIC_URL) },
    )

    private fun ready(vararg platforms: String) =
        LxSourceState.Ready(info(*platforms))

    @Test
    fun `音源没就绪时给全量而不是空列表`() {
        // 用户还没勾音源，给空列表等于啥都搜不了，体验上比给全量差得多
        assertEquals(5, searchablePlatforms(null).size)
        assertEquals(5, searchablePlatforms(LxSourceState.Idle).size)
        assertEquals(5, searchablePlatforms(LxSourceState.Loading).size)
    }

    @Test
    fun `音源加载失败也先给全量`() {
        val failed = LxSourceState.Failed("脚本初始化失败")
        assertEquals(5, searchablePlatforms(failed).size)
    }

    @Test
    fun `取交集`() {
        // 音源只支持 kw/wy，取交集后只剩这两个
        val result = searchablePlatforms(ready("kw", "wy"))
        assertEquals(listOf("kw", "wy"), result)
    }

    @Test
    fun `顺序按我们的搜索实现来不是音源声明顺序`() {
        // 音源声明 tx,kg 的顺序和 UI 顺序不同，输出仍要是 UI 顺序
        val result = searchablePlatforms(ready("tx", "kg", "wy"))
        assertEquals(listOf("kg", "tx", "wy"), result)
    }

    @Test
    fun `音源支持的平台我们没实现搜索就跳过`() {
        // qdy 额外声明了 qsvip（汽水），但唯一支持它的音源后端已 404，
        // 搜得到播不了，所以不做这个平台 —— 不该出现在标签里。
        val result = searchablePlatforms(ready("kw", "qsvip", "wy"))
        assertEquals(listOf("kw", "wy"), result)
    }

    @Test
    fun `local 不该出现在搜索标签里`() {
        val result = searchablePlatforms(ready("kw", "local"))
        assertEquals(listOf("kw"), result)
    }

    @Test
    fun `音源一个远端平台都不支持时给空列表`() {
        // 这种情况 UI 要走 needsSource 分支提示，不能装作正常
        val result = searchablePlatforms(ready("local"))
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun `只声明 lyric 不算可用平台`() {
        // 平台的 actions 里没有 musicUrl 就换不了直链，不算
        val info = LxSourceInfo(
            name = "只给歌词",
            description = "",
            author = "",
            version = "",
            homepage = "",
            qualitys = mapOf("kw" to listOf("128k")),
            actions = mapOf("kw" to listOf(LxSourceInfo.ACTION_LYRIC)),
        )
        assertEquals(emptyList<String>(), searchablePlatforms(LxSourceState.Ready(info)))
    }
}

package cn.music.audioworkshop.domain.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LyricsMatch] 的回归测试。
 *
 * 用的是真实数据：酷我搜「信 / 张月 / 194s」，QQ 搜「信 张月」返回
 * 「信 / 张月 / 194s」和「离歌 / 信乐团 / 267s」。第一条该配对，第二条绝不能配。
 */
class LyricsMatchTest {

    @Test
    fun `同名同歌手同时长应该配对`() {
        assertTrue(
            LyricsMatch.isSameSong(
                queryTitle = "信", queryArtist = "张月", queryDurationMs = 194_000,
                hitTitle = "信", hitArtist = "张月", hitDurationMs = 194_000,
            )
        )
    }

    @Test
    fun `歌手名字不同就不能配`() {
        // 网易有首《信》是 Echo艾歌 唱的，同名同长度，但歌手不是张月
        assertFalse(
            LyricsMatch.isSameSong(
                queryTitle = "信", queryArtist = "张月", queryDurationMs = 194_000,
                hitTitle = "信", hitArtist = "Echo艾歌", hitDurationMs = 195_000,
            )
        )
    }

    @Test
    fun `信乐团不能配到歌名叫信的曲子`() {
        // 歌名相同、歌手包含「信」字，但方向反过来就不该成立
        assertFalse(
            LyricsMatch.isSameSong(
                queryTitle = "信", queryArtist = "张月", queryDurationMs = 194_000,
                hitTitle = "信", hitArtist = "信乐团", hitDurationMs = 194_000,
            )
        )
    }

    @Test
    fun `时长差太多不能配`() {
        assertFalse(
            LyricsMatch.durationMatches(queryDurationMs = 194_000, hitDurationMs = 267_000)
        )
    }

    @Test
    fun `时长差在容差内可以配`() {
        assertTrue(
            LyricsMatch.durationMatches(queryDurationMs = 194_000, hitDurationMs = 200_000)
        )
    }

    @Test
    fun `查不到时长就跳过时长判断`() {
        assertTrue(LyricsMatch.durationMatches(null, 267_000))
        assertTrue(LyricsMatch.durationMatches(194_000, null))
    }

    @Test
    fun `Live 后缀差异应该算同一首`() {
        assertTrue(LyricsMatch.titleMatches("信", "信 (Live)"))
        assertTrue(LyricsMatch.titleMatches("离歌（Live版）", "离歌"))
        assertTrue(LyricsMatch.titleMatches("My Heart", "My Heart"))
    }

    @Test
    fun `书名号和空格不影响歌名匹配`() {
        assertTrue(LyricsMatch.titleMatches("《信》", "信"))
        assertTrue(LyricsMatch.titleMatches("Crazier  ", "Crazier"))
    }

    @Test
    fun `歌名不同不能配`() {
        assertFalse(LyricsMatch.titleMatches("信", "离歌"))
        assertFalse(LyricsMatch.titleMatches("天生刺猬", "信"))
    }

    @Test
    fun `单字歌名靠时长兜底不靠歌名长度`() {
        // 「信」对「信 (Live)」：歌名只差后缀，时长差得多就该否掉
        assertTrue(LyricsMatch.titleMatches("信", "信 (Live)"))
        assertFalse(
            LyricsMatch.isSameSong(
                queryTitle = "信", queryArtist = "张月", queryDurationMs = 194_000,
                hitTitle = "信 (Live)", hitArtist = "张月", hitDurationMs = 260_000,
            )
        )
    }

    @Test
    fun `多歌手的任一歌手对上就算配对`() {
        // 原曲标了三个歌手，QQ 只标了其中一个 —— 合唱版常常这样
        assertTrue(LyricsMatch.artistMatches("韩磊、张月、潘倩倩", "韩磊"))
    }

    @Test
    fun `结果歌手是原曲歌手的超集也不能算同一首配音`() {
        // 方向只做「结果 → 原曲」，反方向会误判
        assertFalse(LyricsMatch.artistMatches("张月", "张月、潘倩倩"))
    }

    @Test
    fun `歌手查不到就不拦 交给其他条件`() {
        assertTrue(LyricsMatch.artistMatches(null, "张月"))
        assertTrue(LyricsMatch.artistMatches("张月", null))
    }

    @Test
    fun `归一化去掉全部装饰`() {
        assertTrue(LyricsMatch.normalize("《信》-Live (Remix)") == "信liveremix")
    }
}

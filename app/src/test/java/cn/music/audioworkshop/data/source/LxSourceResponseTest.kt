package cn.music.audioworkshop.data.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 锁住音源回包的取值形状。
 *
 * 这几个字段路径写错时 Kotlin 不会报错，只会静默返回 null，
 * 表现是「音源明明给了地址，App 却说解析失败」——很难从界面看出来。
 * 形状取自 assets/lx/user-api-preload.js 的 handleRequest。
 */
class LxSourceResponseTest {

    @Test
    fun `musicUrl 从 data 里取，不是顶层`() {
        val result = JSONObject()
            .put("source", "wy")
            .put("action", "musicUrl")
            .put("data", JSONObject().put("type", "320k").put("url", "https://cdn/song.mp3"))

        assertEquals("https://cdn/song.mp3", result.extractMusicUrl())
    }

    @Test
    fun `顶层没有 url 时不返回 null 内容以外的脏值`() {
        // 之前就是这里写成 getString("url")，顶层根本没有这个字段。
        val result = JSONObject().put("source", "wy").put("action", "musicUrl")

        assertNull(result.extractMusicUrl())
    }

    @Test
    fun `lyric 从 data 里取`() {
        val result = JSONObject()
            .put("source", "local")
            .put("action", "lyric")
            .put("data", JSONObject().put("lyric", "[00:01.00]词").put("tlyric", JSONObject.NULL))

        assertEquals("[00:01.00]词", result.extractLyric())
    }

    @Test
    fun `pic 的 data 本身就是字符串，不是对象`() {
        val result = JSONObject()
            .put("source", "local")
            .put("action", "pic")
            .put("data", "https://cdn/cover.jpg")

        assertEquals("https://cdn/cover.jpg", result.extractPic())
    }

    @Test
    fun `pic 遇到对象形状时不误取`() {
        val result = JSONObject().put("data", JSONObject().put("url", "https://cdn/cover.jpg"))

        assertNull(result.extractPic())
    }

    @Test
    fun `空回包不炸`() {
        assertNull((null as JSONObject?).extractMusicUrl())
        assertNull((null as JSONObject?).extractLyric())
        assertNull((null as JSONObject?).extractPic())
    }

    @Test
    fun `空字符串不当作有效地址`() {
        val result = JSONObject().put("data", JSONObject().put("url", ""))

        assertNull(result.extractMusicUrl())
    }
}
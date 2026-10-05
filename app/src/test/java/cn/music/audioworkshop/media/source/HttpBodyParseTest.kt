package cn.music.audioworkshop.media.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「代脚本发 HTTP」的回包 body 解析行为。
 *
 * 对齐 lx 的 `src/core/init/userApi/request.js`：
 * `try { resp.body = JSON.parse(resp.body) } catch {}`
 *
 * 这条要是漏了，脚本里所有 `r.body.xxx` 全是 undefined，
 * 表现是音源初始化就失败（`r.body.code !== 200` 恒成立），
 * 看起来像源本身坏了。
 */
class HttpBodyParseTest {

    @Test
    fun `对象文本解析成对象`() {
        val body = HttpBodyParse.parse("""{"code":200,"data":{"url":"https://cdn/a.mp3"}}""")

        assertTrue(body is org.json.JSONObject)
        assertEquals(200, (body as org.json.JSONObject).getInt("code"))
        assertEquals(
            "https://cdn/a.mp3",
            body.getJSONObject("data").getString("url"),
        )
    }

    @Test
    fun `数组文本解析成数组`() {
        val body = HttpBodyParse.parse("""[{"a":1},{"a":2}]""")

        assertTrue(body is org.json.JSONArray)
        assertEquals(2, (body as org.json.JSONArray).length())
    }

    @Test
    fun `非 JSON 原样给字符串`() {
        // 音源也会返回纯文本/HTML，取不到字段时至少别抛异常。
        assertEquals("<html>404</html>", HttpBodyParse.parse("<html>404</html>"))
    }

    @Test
    fun `空 body 给 NULL`() {
        assertEquals(org.json.JSONObject.NULL, HttpBodyParse.parse(""))
        assertEquals(org.json.JSONObject.NULL, HttpBodyParse.parse("   "))
    }

    @Test
    fun `JSON null 归一成 NULL`() {
        assertEquals(org.json.JSONObject.NULL, HttpBodyParse.parse("null"))
    }

    @Test
    fun `坏 JSON 不抛异常`() {
        // 截断的 JSON 在真实网络里很常见（响应被截断）
        assertEquals("""{"code":200""", HttpBodyParse.parse("""{"code":200"""))
    }

    @Test
    fun `带前后空白的对象照样解析`() {
        val body = HttpBodyParse.parse("""  {"code":200}  """)

        assertTrue(body is org.json.JSONObject)
    }

    @Test
    fun `数字文本解析成数字`() {
        assertEquals(1416.0, (HttpBodyParse.parse("1416") as Number).toDouble(), 0.001)
    }
}

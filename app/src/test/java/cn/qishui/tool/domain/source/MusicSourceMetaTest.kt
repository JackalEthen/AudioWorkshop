package cn.qishui.tool.domain.source

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实 lx 音源脚本的兼容性检查。
 *
 * 用线上真实脚本（pdone/lx-music-source 的 huibq 源）跑，
 * 保证元信息解析、文件名兜底、稳定性 id 这些纯逻辑不会退化。
 */
class MusicSourceMetaTest {

    /** 真实脚本的头部，字段名按 lx 社区惯例。 */
    private val realHeader = """
        /*!
         * @name Huibq_lxmusic源
         * @description Github：给大家提供可用源，禁止非法下载
         * @version v1.2.0
         * @author Huibq
         */
    """.trimIndent()

    @Test
    fun 能解析头部注释里的元信息() {
        val meta = MusicSourceMeta.parse(realHeader, "fallback.js")
        assertEquals("Huibq_lxmusic源", meta.name)
        assertEquals("v1.2.0", meta.version)
        assertEquals("Huibq", meta.author)
        assertTrue(meta.description.contains("Github"))
    }

    @Test
    fun 缺头部时用文件名兜底() {
        val meta = MusicSourceMeta.parse("const a = 1", "latest.js")
        assertEquals("latest.js", meta.name)
        assertEquals("", meta.version)
        assertEquals("", meta.author)
    }

    @Test
    fun homepage缺失时为空而不是崩() {
        val meta = MusicSourceMeta.parse(realHeader, "x.js")
        assertEquals("", meta.homepage)
    }

    @Test
    fun 同一份脚本得到同一个id() {
        val a = MusicSourceMeta.stableId(MusicSourceMeta.parse(realHeader, "x"), realHeader)
        val b = MusicSourceMeta.stableId(MusicSourceMeta.parse(realHeader, "x"), realHeader)
        assertEquals("重复导入必须覆盖同一行，不能产生两条", a, b)
    }

    @Test
    fun 不同脚本得到不同id() {
        val a = MusicSourceMeta.stableId(MusicSourceMeta.parse(realHeader, "x"), realHeader)
        val b = MusicSourceMeta.stableId(MusicSourceMeta.parse(realHeader, "x"), "$realHeader\n// 改了一点")
        assertTrue("不同脚本不能撞 id", a != b)
    }

    /**
     * 真实链路：走 OkHttp 下载线上脚本，确认拿到的是能解析的 JS。
     * 需要网络，失败时跳过而不是让 CI 挂掉。
     */
    @Test
    fun 线上脚本能下载且能解析() {
        val url = "https://ghproxy.net/raw.githubusercontent.com/pdone/lx-music-source/main/huibq/latest.js"
        val response = runCatching {
            OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
        }.getOrNull()
        if (response == null || !response.isSuccessful) {
            println("跳过：当前环境访问不了 $url")
            return
        }
        val body = response.body?.string().orEmpty()
        response.close()
        assertTrue("脚本不该是空的", body.isNotBlank())
        assertTrue("应该是 JS 脚本", body.contains("EVENT_NAMES"))

        val meta = MusicSourceMeta.parse(body, url.substringAfterLast('/'))
        println("线上脚本解析结果: name=${meta.name} v=${meta.version} author=${meta.author}")
        assertTrue("应该能解析出名字", meta.name.isNotBlank())
    }
}

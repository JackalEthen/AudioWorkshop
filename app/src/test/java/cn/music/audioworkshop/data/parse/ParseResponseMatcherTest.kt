package cn.music.audioworkshop.data.parse

import cn.music.audioworkshop.domain.model.FieldMapping
import cn.music.audioworkshop.domain.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字段匹配引擎。
 *
 * 测试里的 JSON 是从 api.bugpk.com 实际抓的返回，不是手编的理想结构 ——
 * 只有真实响应才能证明候选字段名够用。
 */
class ParseResponseMatcherTest {

    private val matcher = ParseResponseMatcher()

    /** 163_music 真实返回：根对象直出，用 `status`/`ar_name`/`al_name`/`pic`。 */
    private val real163Music = """
        {
          "status": 200,
          "name": "晴天",
          "ar_name": "周杰伦",
          "al_name": "叶惠美",
          "pic": "https://p3.music.126.net/ZGffiDQZrGj5s_hnR1CNbg==/109951165566379710.jpg",
          "url": "https://music.163.com/song/media/outer/url?id=186016.mp3",
          "size": "8.5 MB",
          "level": "标准音质",
          "lyric": "[00:00.000] 作词 : 周杰伦",
          "cache_status": "rebuilt",
          "bktip": {"Auther":"BugPk-Api","tip":"本接口由BugPk-Api提供服务"}
        }
    """.trimIndent()

    /** kuwo 真实返回：裹在 data 里，错误用 `message` 而非 `msg`。 */
    private val realKuwoError = """
        {"code": 1002, "message": "无法从URL中提取歌曲ID", "data": null}
    """.trimIndent()

    /** douyin 真实返回：data 是空数组而不是 null。 */
    private val realDouyinError = """
        {"code": 400, "msg": "链接格式错误，无法提取ID。", "data": []}
    """.trimIndent()

    @Test
    fun `匹配真实163返回`() {
        val track = matcher.match(real163Music)
        assertEquals("晴天", track.title)
        assertEquals("周杰伦", track.artist)
        assertEquals(
            "https://music.163.com/song/media/outer/url?id=186016.mp3",
            track.audioUrl,
        )
        assertEquals("标准音质", track.quality)
        assertNotNull("封面应被识别", track.artistAvatarUrls.firstOrNull())
        assertTrue("歌词应被识别", track.lyrics.orEmpty().contains("周杰伦"))
        // "8.5 MB" 必须能解析成字节，纯 toLongOrNull 会得到 null
        assertEquals((8.5 * 1024 * 1024).toLong(), track.sizeBytes)
    }

    @Test
    fun `错误信息两种字段名都能读到`() {
        val kuwo = runCatching { matcher.match(realKuwoError) }.exceptionOrNull()
        assertTrue("应抛出可读错误：${kuwo?.message}", kuwo?.message.orEmpty().contains("无法从URL中提取歌曲ID"))

        val douyin = runCatching { matcher.match(realDouyinError) }.exceptionOrNull()
        assertTrue("应抛出可读错误：${douyin?.message}", douyin?.message.orEmpty().contains("链接格式错误"))
    }

    @Test
    fun `data为空数组时不会被当成空内容`() {
        val error = runCatching { matcher.match(realDouyinError) }.exceptionOrNull()
        assertNotNull("空数组不应被当成有效内容", error)
    }

    @Test
    fun `常见的包裹结构都能找到正文`() {
        // data 直接是对象
        val wrapped = """{"code":200,"data":{"title":"A","artist":"B","url":"https://x/a.mp3"}}"""
        assertEquals("A", matcher.match(wrapped).title)

        // data.data 再套一层
        val double = """{"code":200,"data":{"data":{"title":"C","url":"https://x/c.mp3"}}}"""
        assertEquals("C", matcher.match(double).title)

        // data.list[0]
        val list = """{"code":200,"data":{"list":[{"title":"D","url":"https://x/d.mp3"}]}}"""
        assertEquals("D", matcher.match(list).title)

        // data 是数组
        val arr = """{"code":200,"data":[{"title":"E","url":"https://x/e.mp3"}]}"""
        assertEquals("E", matcher.match(arr).title)
    }

    @Test
    fun `不同平台的字段命名都能识别`() {
        // 抖音系常见命名
        val douyinStyle = """
            {"code":200,"data":{
              "aweme_id":"123","desc":"视频标题",
              "music":{"title":"音乐名","author":"音乐作者","play_url":"https://x/song.mp3"},
              "cover":{"url_list":["https://x/cover.jpg"]}
            }}
        """.trimIndent()
        val douyin = matcher.match(douyinStyle)
        assertEquals("音乐名", douyin.title)
        assertEquals("音乐作者", douyin.artist)
        assertEquals("https://x/song.mp3", douyin.audioUrl)

        // B站常见命名
        val biliStyle = """
            {"code":0,"data":{"title":"BV视频","author":"UP主","pic":"https://x/b.jpg",
             "dash":{"audio":[{"baseUrl":"https://x/audio.m4s"}]}}}
        """.trimIndent()
        val bili = matcher.match(biliStyle)
        assertEquals("BV视频", bili.title)
        assertEquals("UP主", bili.artist)

        // 快手/小红书系：play_url + cover 数组
        val ksStyle = """
            {"status":1,"data":{"songName":"KS歌","authorName":"KS作者",
             "playUrl":"https://x/ks.mp3","coverUrl":"https://x/ks.jpg"}}
        """.trimIndent()
        val ks = matcher.match(ksStyle)
        assertEquals("KS歌", ks.title)
        assertEquals("KS作者", ks.artist)
    }

    @Test
    fun `自定义字段名覆盖内置候选`() {
        // 注意键名不能是 song 之类 —— 它同时出现在 TITLE 和 AUDIO_URL 的
        // 内置候选里，两个语义会撞车。这是测试数据要避开的坑。
        val body = """{"code":200,"data":{"my_title":"自定义标题","play_url":"https://x/a.mp3"}}"""
        // 内置候选找不到 my_title，指定后才认出来
        assertNull("未指定映射时应读不到 my_title", matcher.match(body).title)

        val custom = ParseResponseMatcher(FieldMapping(title = "my_title"))
        assertEquals("自定义标题", custom.match(body).title)
    }

    @Test
    fun `自定义候选支持逗号分隔的多个键`() {
        val body = """{"code":200,"data":{"b_url":"https://x/b.mp3","title":"T"}}"""
        // 按顺序试 url,play_url,b_url，第三个才命中
        val custom = ParseResponseMatcher(FieldMapping(audioUrl = "url,play_url,b_url"))
        assertEquals("https://x/b.mp3", custom.match(body).audioUrl)
    }

    @Test
    fun `没有播放地址时明确报错`() {
        val error = runCatching {
            matcher.match("""{"code":200,"data":{"title":"只有标题"}}""")
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(
            "应说明缺播放地址：${error?.message}",
            error?.message.orEmpty().contains("播放地址"),
        )
    }

    @Test
    fun `非JSON给出明确报错`() {
        val error = runCatching { matcher.match("<html>502</html>") }.exceptionOrNull()
        assertTrue(
            "应说明不是 JSON：${error?.message}",
            error?.message.orEmpty().contains("JSON"),
        )
    }

    @Test
    fun `status零和一也视为成功`() {
        assertEquals("T", matcher.match("""{"status":0,"name":"T","url":"https://x/a.mp3"}""").title)
        assertEquals("T", matcher.match("""{"status":1,"name":"T","url":"https://x/a.mp3"}""").title)
        assertEquals("T", matcher.match("""{"code":200,"data":{"name":"T","url":"https://x/a.mp3"}}""").title)
    }

    @Test
    fun `纯数字比特率能读出来`() {
        val body = """{"code":200,"data":{"url":"https://x/a.mp3","bitrate":320000}}"""
        assertEquals(320000L, matcher.match(body).bitrateBps)
        // 字符串形式的数字也要认
        val strBody = """{"code":200,"data":{"url":"https://x/a.mp3","br":"128000"}}"""
        assertEquals(128000L, matcher.match(strBody).bitrateBps)
    }

    @Test
    fun `尺寸字符串多种单位都能解析`() {
        fun sizeOf(raw: String): Long? = matcher.match(
            """{"code":200,"data":{"url":"https://x/a.mp3","size":"$raw"}}""",
        ).sizeBytes

        assertEquals(1024L, sizeOf("1024"))
        assertEquals(1024L, sizeOf("1 KB"))
        assertEquals(2L * 1024 * 1024, sizeOf("2MB"))
        assertNull(sizeOf("未知"))
    }

    @Test
    fun `未知字段不报错只是为空`() {
        val track = matcher.match("""{"code":200,"data":{"url":"https://x/a.mp3"}}""")
        assertEquals("https://x/a.mp3", track.audioUrl)
        assertNull("缺失的字段应为 null 而不是崩溃", track.title)
        assertNull(track.artist)
        assertEquals(emptyList<String>(), track.artistAvatarUrls)
    }

    @Test
    fun `图文源的多张图片全部收进mediaUrls且首个是audioUrl`() {
        val track = matcher.match(
            """
            {"code":200,"data":{
              "title":"图集",
              "url":"https://cdn.x.com/1.jpg",
              "list":[
                {"img":"https://cdn.x.com/2.png"},
                {"img":"https://cdn.x.com/3.webp"}
              ]
            }}
            """.trimIndent(),
        )

        assertEquals(MediaKind.IMAGE, track.mediaKind)
        assertEquals("https://cdn.x.com/1.jpg", track.audioUrl)
        assertEquals(
            listOf(
                "https://cdn.x.com/1.jpg",
                "https://cdn.x.com/2.png",
                "https://cdn.x.com/3.webp",
            ),
            track.mediaUrls,
        )
    }

    @Test
    fun `音频源只收一个地址不混入封面等图片`() {
        val track = matcher.match(
            """
            {"code":200,"data":{
              "title":"晴天",
              "url":"https://music.163.com/song/media/outer/url?id=186016.mp3",
              "pic":"https://p3.music.126.net/cover.jpg",
              "avatar":"https://p3.music.126.net/avatar.png"
            }}
            """.trimIndent(),
        )

        assertEquals(MediaKind.AUDIO, track.mediaKind)
        assertEquals(listOf("https://music.163.com/song/media/outer/url?id=186016.mp3"), track.mediaUrls)
    }

    @Test
    fun `图片地址重复时只保留一份`() {
        val track = matcher.match(
            """
            {"code":200,"data":{
              "url":"https://cdn.x.com/1.jpg",
              "more":["https://cdn.x.com/1.jpg","https://cdn.x.com/2.jpg"]
            }}
            """.trimIndent(),
        )

        assertEquals(
            listOf("https://cdn.x.com/1.jpg", "https://cdn.x.com/2.jpg"),
            track.mediaUrls,
        )
    }
}

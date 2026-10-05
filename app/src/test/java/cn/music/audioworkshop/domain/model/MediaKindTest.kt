package cn.music.audioworkshop.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体类型判定。
 *
 * 字段名区分不出视频和音频（都叫 url），全靠扩展名，所以这里覆盖到每种情况 ——
 * 判错的后果是把视频当音乐存进播放列表，或者反过来下载失败。
 */
class MediaKindTest {

    @Test
    fun `按格式字段判定`() {
        assertEquals(MediaKind.VIDEO, MediaKind.detect("mp4", null))
        assertEquals(MediaKind.VIDEO, MediaKind.detect("FLV", null))
        assertEquals(MediaKind.IMAGE, MediaKind.detect("jpg", null))
        assertEquals(MediaKind.IMAGE, MediaKind.detect("PNG", null))
        assertEquals(MediaKind.AUDIO, MediaKind.detect("mp3", null))
        assertEquals(MediaKind.AUDIO, MediaKind.detect("flac", null))
    }

    @Test
    fun `格式字段缺失时看URL后缀`() {
        assertEquals(MediaKind.VIDEO, MediaKind.detect(null, "https://x.com/a/video.mp4"))
        assertEquals(MediaKind.IMAGE, MediaKind.detect(null, "https://cdn.x.com/pic/cover.webp"))
        assertEquals(MediaKind.AUDIO, MediaKind.detect(null, "https://m.x.com/song.mp3"))
    }

    @Test
    fun `URL带查询参数时也能取到扩展名`() {
        // ?a=b 后面不能再当扩展名的一部分
        assertEquals(MediaKind.AUDIO, MediaKind.detect(null, "https://x.com/song.mp3?token=abc123"))
        assertEquals(MediaKind.VIDEO, MediaKind.detect(null, "https://x.com/v.mp4?sign=xyz#t=1"))
    }

    @Test
    fun `格式字段优先于URL`() {
        // 接口自己说的更准：URL 看着像图片，format 说mp4 就是视频
        assertEquals(MediaKind.VIDEO, MediaKind.detect("mp4", "https://x.com/cover.jpg"))
    }

    @Test
    fun `都认不出时按音频处理`() {
        // 音乐是主场景，猜错的代价最小
        assertEquals(MediaKind.AUDIO, MediaKind.detect(null, null))
        assertEquals(MediaKind.AUDIO, MediaKind.detect("", ""))
        assertEquals(MediaKind.AUDIO, MediaKind.detect("unknown", "https://x.com/download"))
        assertEquals(MediaKind.AUDIO, MediaKind.detect(null, "https://x.com/stream"))
    }

    @Test
    fun `带前导点的格式能识别`() {
        assertEquals(MediaKind.VIDEO, MediaKind.detect(".mp4", null))
        assertEquals(MediaKind.IMAGE, MediaKind.detect(".jpg", null))
    }

    @Test
    fun `流媒体可播放但不可下载`() {
        assertEquals(MediaKind.VIDEO, MediaKind.detect("m3u8", null))
        assertFalse("m3u8 是播放清单，下载下来不能离线播放", MediaKind.isDownloadable("m3u8", null))
        assertFalse("mpd 同理", MediaKind.isDownloadable("mpd", null))
        assertTrue("普通视频可下载", MediaKind.isDownloadable("mp4", null))
        assertTrue("音频永远可下载", MediaKind.isDownloadable("mp3", null))
        assertTrue("图片可下载", MediaKind.isDownloadable("jpg", null))
    }

    @Test
    fun `URL没有扩展名但format是流媒体时也不可下载`() {
        assertFalse(
            MediaKind.isDownloadable("m3u8", "https://live.x.com/hls/playlist"),
        )
    }
}

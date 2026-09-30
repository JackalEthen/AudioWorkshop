package cn.qishui.tool.data.media

import cn.qishui.tool.domain.lyrics.LyricsParser
import cn.qishui.tool.domain.model.LyricsTrack

/**
 * 歌词解析入口：标准 LRC 和 QRC 逐字都认。
 *
 * **为什么不能只用一个。** 线上拿到的歌词一律是标准 LRC —— QQ 官方
 * `fcg_query_lyric_new.fcg`、网易官方 `song/lyric`、amll 库、本地文件内嵌标签，
 * 全是 `[mm:ss.xx]歌词`。而 [QsmusicLyricParser] 只认 QRC
 * （`[起,时]` + `<相对,时,0>字`），碰上标准 LRC 会因为时间戳里没有逗号而整行丢弃，
 * 结果就是「接口返回了 938 字符，播放页一个字都没有」。
 *
 * 所以先按 LRC 试，解析不出再走 QRC。两种格式的行数不可能同时非零，
 * 按结果判比按来源猜可靠 —— 本地文件两种格式都见过。
 */
object LyricsCodec : LyricsParser {

    override fun parse(raw: String?): LyricsTrack {
        if (raw.isNullOrBlank()) return LyricsTrack.EMPTY
        return LrcCodec.parse(raw).takeIf { it.lines.isNotEmpty() }
            ?: QsmusicLyricParser().parse(raw)
    }
}

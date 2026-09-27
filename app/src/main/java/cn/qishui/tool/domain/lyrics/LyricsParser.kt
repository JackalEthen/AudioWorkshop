package cn.qishui.tool.domain.lyrics

import cn.qishui.tool.domain.model.LyricsTrack

interface LyricsParser {
    fun parse(raw: String?): LyricsTrack
}

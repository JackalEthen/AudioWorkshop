package cn.music.audioworkshop.domain.lyrics

import cn.music.audioworkshop.domain.model.LyricsTrack

interface LyricsParser {
    fun parse(raw: String?): LyricsTrack
}

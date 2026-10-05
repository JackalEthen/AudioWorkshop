package cn.music.audioworkshop.feature.edit.export

import cn.music.audioworkshop.domain.model.SourceTrack

const val MAX_JOIN_SOURCES = 8

object JoinSourceOrder {

    fun order(preselected: List<SourceTrack>): List<SourceTrack> =
        preselected.take(MAX_JOIN_SOURCES).distinctBy { it.id }
}

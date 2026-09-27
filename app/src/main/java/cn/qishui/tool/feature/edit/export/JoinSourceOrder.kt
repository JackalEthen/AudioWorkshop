package cn.qishui.tool.feature.edit.export

import cn.qishui.tool.domain.model.SourceTrack

const val MAX_JOIN_SOURCES = 8

object JoinSourceOrder {

    fun order(preselected: List<SourceTrack>): List<SourceTrack> =
        preselected.take(MAX_JOIN_SOURCES).distinctBy { it.id }
}

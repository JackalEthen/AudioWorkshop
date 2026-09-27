package cn.qishui.tool.domain

import cn.qishui.tool.domain.model.ResolvedTrack

interface MusicResolver {
    suspend fun resolve(shareInput: String): Result<ResolvedTrack>
}

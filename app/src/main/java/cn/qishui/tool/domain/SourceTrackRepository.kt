package cn.qishui.tool.domain

import cn.qishui.tool.domain.model.SourceTrack
import kotlinx.coroutines.flow.Flow

interface SourceTrackRepository {
    fun observeAll(): Flow<List<SourceTrack>>
    suspend fun getById(id: String): SourceTrack?
    suspend fun upsert(track: SourceTrack)
    suspend fun importLocalAudio(uri: String): Result<SourceTrack>
    suspend fun delete(id: String)
}

package cn.qishui.tool.data.repository

import android.net.Uri
import cn.qishui.tool.data.local.SourceTrackDao
import cn.qishui.tool.data.local.toDomain
import cn.qishui.tool.data.local.toEntity
import cn.qishui.tool.data.media.LocalAudioImporter
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.model.SourceTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSourceTrackRepository(
    private val dao: SourceTrackDao,
    private val localAudioImporter: LocalAudioImporter,
) : SourceTrackRepository {
    override fun observeAll(): Flow<List<SourceTrack>> = dao.observeAll().map { tracks ->
        tracks.map { it.toDomain() }
    }

    override suspend fun getById(id: String): SourceTrack? = dao.getById(id)?.toDomain()

    override suspend fun upsert(track: SourceTrack) {
        val createdAt = dao.getById(track.id)?.created_at ?: System.currentTimeMillis()
        dao.insert(track.toEntity(createdAt))
    }

    override suspend fun importLocalAudio(uri: String): Result<SourceTrack> = runCatching {
        val track = localAudioImporter.import(Uri.parse(uri)).getOrThrow()
        upsert(track)
        track
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}

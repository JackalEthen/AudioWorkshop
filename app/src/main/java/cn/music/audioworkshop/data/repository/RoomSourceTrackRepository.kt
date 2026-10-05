package cn.music.audioworkshop.data.repository

import android.net.Uri
import cn.music.audioworkshop.data.local.SourceTrackDao
import cn.music.audioworkshop.data.local.toDomain
import cn.music.audioworkshop.data.local.toEntity
import cn.music.audioworkshop.data.media.AudioImporter
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.domain.model.SourceTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSourceTrackRepository(
    private val dao: SourceTrackDao,
    private val localAudioImporter: AudioImporter,
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
        val track = localAudioImporter.import(uri).getOrThrow()
        upsert(track)
        track
    }

    /**
     * 导入但不写库。编辑页用：音频是一次性的，不能出现在播放器列表里。
     *
     * 之前编辑页和播放器共用 [importLocalAudio]，两边都往 source_tracks 写，
     * 同一个 `source_code = "local"`、同一个入库路径，播放器根本分不出是哪来的，
     * 于是编辑页导入的歌全跑进了播放列表。
     */
    override suspend fun importLocalAudioEphemeral(uri: String): Result<SourceTrack> = runCatching {
        localAudioImporter.import(uri).getOrThrow()
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}

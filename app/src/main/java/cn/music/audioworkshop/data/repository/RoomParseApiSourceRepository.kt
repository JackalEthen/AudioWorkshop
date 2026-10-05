package cn.music.audioworkshop.data.repository

import cn.music.audioworkshop.data.local.ParseApiSourceDao
import cn.music.audioworkshop.data.local.ParseApiSourceEntity
import cn.music.audioworkshop.domain.model.FieldMapping
import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.parse.ParseApiSourceRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomParseApiSourceRepository(
    private val dao: ParseApiSourceDao,
) : ParseApiSourceRepository {

    override fun observeAll(): Flow<List<ParseApiSource>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEnabled(): Flow<List<ParseApiSource>> =
        dao.observeEnabled().map { list -> list.map { it.toDomain() } }

    override fun observeEnabledCount(): Flow<Int> = dao.observeEnabledCount()

    override suspend fun upsert(source: ParseApiSource) {
        val existing = dao.byId(source.id)
        dao.upsert(
            source.toEntity(
                sortOrder = existing?.sort_order ?: dao.nextSortOrder(),
                createdAt = existing?.created_at ?: System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun delete(id: String) {
        dao.byId(id)?.let { dao.delete(it) }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.byId(id)?.let { dao.update(it.copy(enabled = enabled)) }
    }

    override suspend fun byId(id: String): ParseApiSource? = dao.byId(id)?.toDomain()
}

private fun ParseApiSourceEntity.toDomain() = ParseApiSource(
    id = id,
    name = name,
    url = url,
    typeParam = type_param,
    apiKey = api_key,
    enabled = enabled,
    note = note,
    fields = FieldMapping(
        title = field_title,
        artist = field_artist,
        album = field_album,
        cover = field_cover,
        audioUrl = field_audio_url,
        lyrics = field_lyrics,
        bitrate = field_bitrate,
        size = field_size,
        quality = field_quality,
        sampleRate = field_sample_rate,
        format = field_format,
        codec = field_codec,
    ),
)

private fun ParseApiSource.toEntity(sortOrder: Int, createdAt: Long) = ParseApiSourceEntity(
    id = id.ifBlank { UUID.randomUUID().toString() },
    name = name,
    url = url,
    type_param = typeParam,
    api_key = apiKey,
    enabled = enabled,
    note = note,
    field_title = fields.title,
    field_artist = fields.artist,
    field_album = fields.album,
    field_cover = fields.cover,
    field_audio_url = fields.audioUrl,
    field_lyrics = fields.lyrics,
    field_bitrate = fields.bitrate,
    field_size = fields.size,
    field_quality = fields.quality,
    field_sample_rate = fields.sampleRate,
    field_format = fields.format,
    field_codec = fields.codec,
    sort_order = sortOrder,
    created_at = createdAt,
)

package cn.music.audioworkshop.data.repository

import cn.music.audioworkshop.data.local.ParseRecordDao
import cn.music.audioworkshop.data.local.ParseRecordEntity
import cn.music.audioworkshop.data.local.toDomain
import cn.music.audioworkshop.domain.ParseRecordRepository
import cn.music.audioworkshop.domain.model.ParseRecord
import cn.music.audioworkshop.domain.model.ResolvedTrack
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomParseRecordRepository(
    private val dao: ParseRecordDao,
) : ParseRecordRepository {
    override fun observeParseRecords(): Flow<List<ParseRecord>> = dao.observeAll().map { records ->
        records.map { it.toDomain() }
    }

    override suspend fun save(track: ResolvedTrack): Result<Unit> = runCatching {
        val sourceShareUrl = track.sourceShareUrl?.takeIf(String::isNotBlank)
            ?: error("解析记录缺少来源链接")
        dao.insert(
            ParseRecordEntity(
                id = UUID.randomUUID().toString(),
                source_share_url = sourceShareUrl,
                title = track.title,
                artist = track.artist,
                format = track.format,
                bitrate = track.bitrateBps,
                size = track.sizeBytes,
                file_hash = track.fileHash,
                lyrics = track.lyrics,
                cache_expire_at = track.cacheExpiresAtEpochSeconds,
                created_at = System.currentTimeMillis(),
            ),
        )
    }
}

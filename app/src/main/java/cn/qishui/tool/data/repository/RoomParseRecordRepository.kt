package cn.qishui.tool.data.repository

import cn.qishui.tool.data.local.ParseRecordDao
import cn.qishui.tool.data.local.ParseRecordEntity
import cn.qishui.tool.data.local.toDomain
import cn.qishui.tool.domain.ParseRecordRepository
import cn.qishui.tool.domain.model.ParseRecord
import cn.qishui.tool.domain.model.ResolvedTrack
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

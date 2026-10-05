package cn.music.audioworkshop.domain

import cn.music.audioworkshop.domain.model.ParseRecord
import cn.music.audioworkshop.domain.model.ResolvedTrack
import kotlinx.coroutines.flow.Flow

interface ParseRecordRepository {
    suspend fun save(track: ResolvedTrack): Result<Unit>
    fun observeParseRecords(): Flow<List<ParseRecord>>
}

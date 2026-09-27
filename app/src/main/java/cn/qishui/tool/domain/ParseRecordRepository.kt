package cn.qishui.tool.domain

import cn.qishui.tool.domain.model.ParseRecord
import cn.qishui.tool.domain.model.ResolvedTrack
import kotlinx.coroutines.flow.Flow

interface ParseRecordRepository {
    suspend fun save(track: ResolvedTrack): Result<Unit>
    fun observeParseRecords(): Flow<List<ParseRecord>>
}

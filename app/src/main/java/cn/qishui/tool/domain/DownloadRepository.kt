package cn.qishui.tool.domain

import cn.qishui.tool.domain.model.DownloadTask
import cn.qishui.tool.domain.model.ResolvedTrack
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    suspend fun enqueue(track: ResolvedTrack): Result<String>
    suspend fun pause(id: String): Result<Unit>
    suspend fun resume(id: String): Result<Unit>
    suspend fun retry(id: String): Result<Unit>
    suspend fun delete(id: String, deleteFile: Boolean): Result<Unit>
    fun observeDownloads(): Flow<List<DownloadTask>>
}

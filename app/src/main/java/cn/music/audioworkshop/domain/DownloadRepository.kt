package cn.music.audioworkshop.domain

import cn.music.audioworkshop.domain.model.DownloadTask
import cn.music.audioworkshop.domain.model.ResolvedTrack
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    suspend fun enqueue(track: ResolvedTrack): Result<String>
    suspend fun pause(id: String): Result<Unit>
    suspend fun resume(id: String): Result<Unit>
    suspend fun retry(id: String): Result<Unit>
    suspend fun delete(id: String, deleteFile: Boolean): Result<Unit>
    fun observeDownloads(): Flow<List<DownloadTask>>
}

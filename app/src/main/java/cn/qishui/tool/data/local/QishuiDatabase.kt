package cn.qishui.tool.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import cn.qishui.tool.domain.model.DownloadStatus
import cn.qishui.tool.domain.model.DownloadTask
import cn.qishui.tool.domain.model.ParseRecord
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["expected_md5"]),
        Index(value = ["temporary_url"]),
        Index(value = ["status"]),
    ],
)
data class DownloadTaskEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String?,
    val artist: String?,
    val source_share_url: String,
    val temporary_url: String?,
    val format: String?,
    val sample_rate_hz: Long?,
    val expected_size: Long?,
    val expected_md5: String?,
    val downloaded_bytes: Long,
    val etag: String?,
    val last_modified: String?,
    val part_path: String?,
    val final_path: String?,
    val status: String,
    val error_message: String?,
    val lyrics: String?,
    val created_at: Long,
    val updated_at: Long,
)

@Entity(tableName = "parse_records")
data class ParseRecordEntity(
    @androidx.room.PrimaryKey val id: String,
    val source_share_url: String,
    val title: String?,
    val artist: String?,
    val format: String?,
    val bitrate: Long?,
    val size: Long?,
    val file_hash: String?,
    val lyrics: String?,
    val cache_expire_at: Long?,
    val created_at: Long,
)

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadTaskEntity?

    @Query(
        """
        SELECT * FROM download_tasks
        WHERE ((:fileHash IS NOT NULL AND expected_md5 = :fileHash)
            OR (:temporaryUrl IS NOT NULL AND temporary_url = :temporaryUrl))
        ORDER BY created_at DESC
        LIMIT 1
        """,
    )
    suspend fun findByIdentity(fileHash: String?, temporaryUrl: String?): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: DownloadTaskEntity)

    @Update
    suspend fun update(task: DownloadTaskEntity)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        UPDATE download_tasks
        SET status = 'PAUSED', error_message = NULL, updated_at = :updatedAt
        WHERE status IN ('QUEUED', 'DOWNLOADING')
        """,
    )
    suspend fun pauseInterrupted(updatedAt: Long)
}

@Dao
interface ParseRecordDao {
    @Query("SELECT * FROM parse_records ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ParseRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ParseRecordEntity)
}

@Database(
    entities = [
        DownloadTaskEntity::class,
        ParseRecordEntity::class,
        SourceTrackEntity::class,
        EditProjectEntity::class,
        ExportPackageEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class QishuiDatabase : RoomDatabase() {
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun parseRecordDao(): ParseRecordDao
    abstract fun sourceTrackDao(): SourceTrackDao
    abstract fun editProjectDao(): EditProjectDao
    abstract fun exportPackageDao(): ExportPackageDao
}

internal fun DownloadTaskEntity.toDomain(): DownloadTask = DownloadTask(
    id = id,
    title = title,
    artist = artist,
    sourceShareUrl = source_share_url,
    temporaryUrl = temporary_url,
    format = format,
    sampleRateHz = sample_rate_hz,
    expectedSizeBytes = expected_size,
    expectedMd5 = expected_md5,
    downloadedBytes = downloaded_bytes,
    eTag = etag,
    lastModified = last_modified,
    partPath = part_path,
    finalPath = final_path,
    status = DownloadStatus.valueOf(status),
    errorMessage = error_message,
    lyrics = lyrics,
    createdAtEpochMillis = created_at,
    updatedAtEpochMillis = updated_at,
)

internal fun DownloadTask.toEntity(): DownloadTaskEntity = DownloadTaskEntity(
    id = id,
    title = title,
    artist = artist,
    source_share_url = sourceShareUrl,
    temporary_url = temporaryUrl,
    format = format,
    sample_rate_hz = sampleRateHz,
    expected_size = expectedSizeBytes,
    expected_md5 = expectedMd5,
    downloaded_bytes = downloadedBytes,
    etag = eTag,
    last_modified = lastModified,
    part_path = partPath,
    final_path = finalPath,
    status = status.name,
    error_message = errorMessage,
    lyrics = lyrics,
    created_at = createdAtEpochMillis,
    updated_at = updatedAtEpochMillis,
)

internal fun ParseRecordEntity.toDomain(): ParseRecord = ParseRecord(
    id = id,
    sourceShareUrl = source_share_url,
    title = title,
    artist = artist,
    format = format,
    bitrateBps = bitrate,
    sizeBytes = size,
    fileHash = file_hash,
    lyrics = lyrics,
    cacheExpiresAtEpochSeconds = cache_expire_at,
    createdAtEpochMillis = created_at,
)

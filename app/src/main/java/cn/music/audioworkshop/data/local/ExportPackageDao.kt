package cn.music.audioworkshop.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.music.audioworkshop.domain.model.ExportPackage
import cn.music.audioworkshop.domain.model.ExportValidationStatus
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "export_packages",
    primaryKeys = ["edit_project_id", "output_path"],
)
data class ExportPackageEntity(
    val edit_project_id: String,
    val output_path: String,
    val format: String,
    val duration_ms: Long,
    val size_bytes: Long,
    val validation_status: String,
    val created_at: Long,
)

@Dao
interface ExportPackageDao {
    @Query("SELECT * FROM export_packages ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ExportPackageEntity>>

    @Query(
        """
        SELECT * FROM export_packages
        WHERE edit_project_id = :editProjectId AND output_path = :outputPath
        LIMIT 1
        """,
    )
    suspend fun get(editProjectId: String, outputPath: String): ExportPackageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pkg: ExportPackageEntity)

    @Query("DELETE FROM export_packages WHERE edit_project_id = :editProjectId AND output_path = :outputPath")
    suspend fun deleteByKey(editProjectId: String, outputPath: String)
}

internal fun ExportPackageEntity.toDomain(): ExportPackage = ExportPackage(
    sourceEditProjectId = edit_project_id,
    outputPath = output_path,
    format = format,
    durationMs = duration_ms,
    sizeBytes = size_bytes,
    createdAt = created_at,
    validationStatus = ExportValidationStatus.valueOf(validation_status),
)

internal fun ExportPackage.toEntity(): ExportPackageEntity = ExportPackageEntity(
    edit_project_id = sourceEditProjectId,
    output_path = outputPath,
    format = format,
    duration_ms = durationMs,
    size_bytes = sizeBytes,
    validation_status = validationStatus.name,
    created_at = createdAt,
)

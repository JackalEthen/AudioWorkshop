package cn.qishui.tool.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.qishui.tool.domain.model.EditOperation
import cn.qishui.tool.domain.model.EditProject
import cn.qishui.tool.domain.model.EditTimeRange
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "edit_projects")
data class EditProjectEntity(
    @androidx.room.PrimaryKey val id: String,
    val source_track_id: String,
    val type: String,
    val segments: String,
    val gain_db: Float?,
    val fade_in_ms: Long?,
    val fade_out_ms: Long?,
    val lyric_offset_ms: Long?,
    val joined_track_ids: String,
    val created_at: Long,
    val updated_at: Long,
)

@Dao
interface EditProjectDao {
    @Query("SELECT * FROM edit_projects ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<EditProjectEntity>>

    @Query("SELECT * FROM edit_projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): EditProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: EditProjectEntity)

    @Update
    suspend fun update(project: EditProjectEntity)

    @Query("DELETE FROM edit_projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

internal fun EditProjectEntity.toDomain(): EditProject = EditProject(
    id = id,
    sourceTrackId = source_track_id,
    type = EditOperation.valueOf(type),
    segments = decodeTimeRanges(segments),
    gainDb = gain_db,
    fadeInMs = fade_in_ms,
    fadeOutMs = fade_out_ms,
    lyricOffsetMs = lyric_offset_ms,
    joinedTrackIds = joined_track_ids.split(',').filter(String::isNotBlank),
    createdAtEpochMillis = created_at,
    updatedAtEpochMillis = updated_at,
)

internal fun EditProject.toEntity(): EditProjectEntity = EditProjectEntity(
    id = id,
    source_track_id = sourceTrackId,
    type = type.name,
    segments = encodeTimeRanges(segments),
    gain_db = gainDb,
    fade_in_ms = fadeInMs,
    fade_out_ms = fadeOutMs,
    lyric_offset_ms = lyricOffsetMs,
    joined_track_ids = joinedTrackIds.joinToString(","),
    created_at = createdAtEpochMillis,
    updated_at = updatedAtEpochMillis,
)

internal fun encodeTimeRanges(ranges: List<EditTimeRange>): String =
    ranges.joinToString(";") { "${it.startUs},${it.endUs}" }

internal fun decodeTimeRanges(value: String): List<EditTimeRange> = value.split(';')
    .mapNotNull { part ->
        val bounds = part.split(',')
        val start = bounds.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val end = bounds.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        EditTimeRange(start, end)
    }

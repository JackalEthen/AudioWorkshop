package cn.music.audioworkshop.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.music.audioworkshop.domain.model.SourceOrigin
import cn.music.audioworkshop.domain.model.SourceTrack
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "source_tracks")
data class SourceTrackEntity(
    @androidx.room.PrimaryKey val id: String,
    val origin: String,
    val source_share_url: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val local_path: String?,
    val format: String?,
    val duration_ms: Long?,
    val bitrate_bps: Long?,
    val size_bytes: Long?,
    val sample_rate_hz: Long?,
    val lyrics: String?,
    val file_hash: String?,
    val cover_uri: String?,
    val created_at: Long,
    /** 来源平台：local / kw / kg / tx / wy / mg。本地导入为 local。 */
    val source_code: String?,
    /** 平台歌曲 id（songmid / hash），lx 音源求播放地址必需。 */
    val platform_song_id: String?,
)

@Dao
interface SourceTrackDao {
    @Query("SELECT * FROM source_tracks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SourceTrackEntity>>

    @Query("SELECT * FROM source_tracks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SourceTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: SourceTrackEntity)

    @Update
    suspend fun update(track: SourceTrackEntity)

    @Query("DELETE FROM source_tracks WHERE id = :id")
    suspend fun deleteById(id: String)
}

internal fun SourceTrackEntity.toDomain(): SourceTrack = SourceTrack(
    id = id,
    origin = SourceOrigin.valueOf(origin),
    sourceShareUrl = source_share_url,
    title = title,
    artist = artist,
    album = album,
    localPath = local_path,
    format = format,
    durationMs = duration_ms,
    bitrateBps = bitrate_bps,
    sizeBytes = size_bytes,
    sampleRateHz = sample_rate_hz,
    lyrics = lyrics,
    fileHash = file_hash,
    coverUri = cover_uri,
    sourceCode = source_code,
    platformSongId = platform_song_id,
)

internal fun SourceTrack.toEntity(createdAt: Long): SourceTrackEntity = SourceTrackEntity(
    id = id,
    origin = origin.name,
    source_share_url = sourceShareUrl,
    title = title,
    artist = artist,
    album = album,
    local_path = localPath,
    format = format,
    duration_ms = durationMs,
    bitrate_bps = bitrateBps,
    size_bytes = sizeBytes,
    sample_rate_hz = sampleRateHz,
    lyrics = lyrics,
    file_hash = fileHash,
    cover_uri = coverUri,
    created_at = createdAt,
    source_code = sourceCode,
    platform_song_id = platformSongId,
)

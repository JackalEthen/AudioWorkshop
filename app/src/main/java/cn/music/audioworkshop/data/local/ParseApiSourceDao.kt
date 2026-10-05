package cn.music.audioworkshop.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ParseApiSourceDao {

    /** 全部源，按排序值和创建时间排。设置页和解析页下拉共用。 */
    @Query("SELECT * FROM parse_api_sources ORDER BY sort_order ASC, created_at ASC")
    fun observeAll(): Flow<List<ParseApiSourceEntity>>

    @Query("SELECT * FROM parse_api_sources WHERE enabled = 1 ORDER BY sort_order ASC, created_at ASC")
    fun observeEnabled(): Flow<List<ParseApiSourceEntity>>

    @Query("SELECT * FROM parse_api_sources WHERE id = :id")
    suspend fun byId(id: String): ParseApiSourceEntity?

    @Query("SELECT COUNT(*) FROM parse_api_sources WHERE enabled = 1")
    fun observeEnabledCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ParseApiSourceEntity)

    @Update
    suspend fun update(entity: ParseApiSourceEntity)

    @Delete
    suspend fun delete(entity: ParseApiSourceEntity)

    @Query("SELECT IFNULL(MAX(sort_order), 0) + 1 FROM parse_api_sources")
    suspend fun nextSortOrder(): Int
}

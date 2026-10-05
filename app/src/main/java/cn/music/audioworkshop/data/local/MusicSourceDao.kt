package cn.music.audioworkshop.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicSourceDao {

    @Query("SELECT * FROM `music_sources` ORDER BY `created_at` DESC")
    fun observeAll(): Flow<List<MusicSourceEntity>>

    /** 选中的那个，最多一行。取不到就是没选。 */
    @Query("SELECT * FROM `music_sources` WHERE `selected` = 1 LIMIT 1")
    fun observeSelected(): Flow<MusicSourceEntity?>

    @Query("SELECT * FROM `music_sources` WHERE `id` = :id")
    suspend fun getById(id: String): MusicSourceEntity?

    @Query("SELECT * FROM `music_sources` WHERE `selected` = 1 LIMIT 1")
    suspend fun getSelected(): MusicSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MusicSourceEntity)

    /**
     * 只让 [id] 这一个生效。
     *
     * 「同一时刻只能用一个」是硬约束，所以在一个事务里先把全部取消再选中，
     * 不靠调用方记得先取消旧的。
     */
    @Transaction
    suspend fun selectOnly(id: String) {
        deselectAll()
        markSelected(id)
    }

    @Query("UPDATE `music_sources` SET `selected` = 0")
    suspend fun deselectAll()

    @Query("UPDATE `music_sources` SET `selected` = 1 WHERE `id` = :id")
    suspend fun markSelected(id: String)

    @Query("UPDATE `music_sources` SET `last_error` = :error WHERE `id` = :id")
    suspend fun setLastError(id: String, error: String?)

    @Query("DELETE FROM `music_sources` WHERE `id` = :id")
    suspend fun delete(id: String)
}

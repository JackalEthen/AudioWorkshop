package cn.music.audioworkshop.domain.parse

import cn.music.audioworkshop.domain.model.FieldMapping
import cn.music.audioworkshop.domain.model.ParseApiSource
import kotlinx.coroutines.flow.Flow

/**
 * 解析源仓库。
 *
 * 不提供任何内置默认源 —— 没有记录时解析页就是空壳，由用户自行添加。
 */
interface ParseApiSourceRepository {
    fun observeAll(): Flow<List<ParseApiSource>>
    fun observeEnabled(): Flow<List<ParseApiSource>>
    fun observeEnabledCount(): Flow<Int>
    suspend fun upsert(source: ParseApiSource)
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun byId(id: String): ParseApiSource?
}

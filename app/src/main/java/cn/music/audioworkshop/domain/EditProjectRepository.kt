package cn.music.audioworkshop.domain

import cn.music.audioworkshop.domain.model.EditProject
import kotlinx.coroutines.flow.Flow

interface EditProjectRepository {
    fun observeAll(): Flow<List<EditProject>>
    suspend fun getById(id: String): EditProject?
    suspend fun upsert(project: EditProject)
    suspend fun delete(id: String)
}

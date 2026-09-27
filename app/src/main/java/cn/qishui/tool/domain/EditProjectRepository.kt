package cn.qishui.tool.domain

import cn.qishui.tool.domain.model.EditProject
import kotlinx.coroutines.flow.Flow

interface EditProjectRepository {
    fun observeAll(): Flow<List<EditProject>>
    suspend fun getById(id: String): EditProject?
    suspend fun upsert(project: EditProject)
    suspend fun delete(id: String)
}

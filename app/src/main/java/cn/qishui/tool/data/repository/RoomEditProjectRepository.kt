package cn.qishui.tool.data.repository

import cn.qishui.tool.data.local.EditProjectDao
import cn.qishui.tool.data.local.toDomain
import cn.qishui.tool.data.local.toEntity
import cn.qishui.tool.domain.EditProjectRepository
import cn.qishui.tool.domain.model.EditProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomEditProjectRepository(
    private val dao: EditProjectDao,
) : EditProjectRepository {
    override fun observeAll(): Flow<List<EditProject>> = dao.observeAll().map { projects ->
        projects.map { it.toDomain() }
    }

    override suspend fun getById(id: String): EditProject? = dao.getById(id)?.toDomain()

    override suspend fun upsert(project: EditProject) {
        dao.insert(project.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}

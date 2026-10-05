package cn.music.audioworkshop.data.repository

import cn.music.audioworkshop.data.local.EditProjectDao
import cn.music.audioworkshop.data.local.toDomain
import cn.music.audioworkshop.data.local.toEntity
import cn.music.audioworkshop.domain.EditProjectRepository
import cn.music.audioworkshop.domain.model.EditProject
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

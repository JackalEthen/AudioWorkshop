package cn.qishui.tool.data.repository

import cn.qishui.tool.data.local.ExportPackageDao
import cn.qishui.tool.data.local.toDomain
import cn.qishui.tool.data.local.toEntity
import cn.qishui.tool.domain.ExportPackageRepository
import cn.qishui.tool.domain.model.ExportPackage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomExportPackageRepository(
    private val dao: ExportPackageDao,
) : ExportPackageRepository {
    override fun observeAll(): Flow<List<ExportPackage>> = dao.observeAll().map { packages ->
        packages.map { it.toDomain() }
    }

    override suspend fun get(editProjectId: String, outputPath: String): ExportPackage? =
        dao.get(editProjectId, outputPath)?.toDomain()

    override suspend fun upsert(pkg: ExportPackage) {
        dao.insert(pkg.toEntity())
    }

    override suspend fun delete(editProjectId: String, outputPath: String) {
        dao.deleteByKey(editProjectId, outputPath)
    }
}

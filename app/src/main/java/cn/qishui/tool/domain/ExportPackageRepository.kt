package cn.qishui.tool.domain

import cn.qishui.tool.domain.model.ExportPackage
import kotlinx.coroutines.flow.Flow

interface ExportPackageRepository {
    fun observeAll(): Flow<List<ExportPackage>>
    suspend fun get(editProjectId: String, outputPath: String): ExportPackage?
    suspend fun upsert(pkg: ExportPackage)
    suspend fun delete(editProjectId: String, outputPath: String)
}

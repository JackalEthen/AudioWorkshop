package cn.qishui.tool.data.repository

import cn.qishui.tool.data.local.ExportPackageDao
import cn.qishui.tool.data.local.ExportPackageEntity
import cn.qishui.tool.domain.model.ExportPackage
import cn.qishui.tool.domain.model.ExportValidationStatus
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomExportPackageRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun upsertStoresEveryMappedField(): Unit = runBlocking {
        val repository = repository()

        repository.upsert(pkg("edit-1", "/cache/export.mp3", sizeBytes = 4_096L))

        assertEquals(pkg("edit-1", "/cache/export.mp3", sizeBytes = 4_096L), repository.get("edit-1", "/cache/export.mp3"))
    }

    @Test
    fun observeAllReturnsMappedRowsNewestFirst(): Unit = runBlocking {
        val repository = repository()
        repository.upsert(pkg("edit-1", "/cache/old.mp3", createdAt = 10L))
        repository.upsert(pkg("edit-2", "/cache/new.mp3", createdAt = 20L, status = ExportValidationStatus.FAILED))

        val observed = repository.observeAll().first()

        assertEquals(listOf("/cache/new.mp3", "/cache/old.mp3"), observed.map { it.outputPath })
        assertEquals(ExportValidationStatus.FAILED, observed.first().validationStatus)
        assertEquals(ExportValidationStatus.PASSED, observed.last().validationStatus)
    }

    @Test
    fun upsertReplacesTheSameKey(): Unit = runBlocking {
        val repository = repository()
        repository.upsert(pkg("edit-1", "/cache/export.mp3", sizeBytes = 1L))

        repository.upsert(pkg("edit-1", "/cache/export.mp3", sizeBytes = 2L))

        assertEquals(1, repository.observeAll().first().size)
        assertEquals(2L, repository.get("edit-1", "/cache/export.mp3")!!.sizeBytes)
    }

    @Test
    fun deleteRemovesOnlyTheMatchingKey(): Unit = runBlocking {
        val repository = repository()
        repository.upsert(pkg("edit-1", "/cache/a.mp3"))
        repository.upsert(pkg("edit-1", "/cache/b.mp3"))

        repository.delete("edit-1", "/cache/a.mp3")

        assertNull(repository.get("edit-1", "/cache/a.mp3"))
        assertEquals(listOf("/cache/b.mp3"), repository.observeAll().first().map { it.outputPath })
    }

    @Test
    fun deleteKeepsTheExportedFileOnDisk(): Unit = runBlocking {
        val repository = repository()
        val output = folder.newFile("export.mp3")
        repository.upsert(pkg("edit-1", output.absolutePath))

        repository.delete("edit-1", output.absolutePath)

        assertTrue(File(output.absolutePath).isFile)
        assertEquals(0, repository.observeAll().first().size)
    }

    private fun repository(): RoomExportPackageRepository = RoomExportPackageRepository(FakeExportPackageDao())

    private fun pkg(
        editProjectId: String,
        outputPath: String,
        createdAt: Long = 1L,
        sizeBytes: Long = 1L,
        status: ExportValidationStatus = ExportValidationStatus.PASSED,
    ): ExportPackage = ExportPackage(
        sourceEditProjectId = editProjectId,
        outputPath = outputPath,
        format = "mp3",
        durationMs = 1_000L,
        sizeBytes = sizeBytes,
        createdAt = createdAt,
        validationStatus = status,
    )

    private class FakeExportPackageDao : ExportPackageDao {
        private val rows = MutableStateFlow<List<ExportPackageEntity>>(emptyList())

        override fun observeAll(): Flow<List<ExportPackageEntity>> = rows

        override suspend fun get(editProjectId: String, outputPath: String): ExportPackageEntity? =
            rows.value.firstOrNull { it.edit_project_id == editProjectId && it.output_path == outputPath }

        override suspend fun insert(pkg: ExportPackageEntity) {
            rows.value = (rows.value.filterNot {
                it.edit_project_id == pkg.edit_project_id && it.output_path == pkg.output_path
            } + pkg).sortedByDescending { it.created_at }
        }

        override suspend fun deleteByKey(editProjectId: String, outputPath: String) {
            rows.value = rows.value.filterNot {
                it.edit_project_id == editProjectId && it.output_path == outputPath
            }
        }
    }
}

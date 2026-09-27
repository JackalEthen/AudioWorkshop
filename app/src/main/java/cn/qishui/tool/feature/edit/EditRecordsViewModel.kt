package cn.qishui.tool.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.domain.EditProjectRepository
import cn.qishui.tool.domain.ExportPackageRepository
import cn.qishui.tool.domain.model.EditProject
import cn.qishui.tool.domain.model.ExportPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class EditProjectRecord(
    val project: EditProject,
    val exports: List<ExportPackage>,
)

data class EditRecordsUiState(
    val groupedProjects: Map<EditFeatureCard, List<EditProjectRecord>> = emptyMap(),
    val isLoading: Boolean = true,
    val message: String? = null,
)

class EditRecordsViewModel(
    private val editProjectRepository: EditProjectRepository,
    private val exportPackageRepository: ExportPackageRepository,
    private val deleteExportFile: (String) -> Boolean = { false },
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<EditRecordsUiState> = combine(
        editProjectRepository.observeAll(),
        exportPackageRepository.observeAll().map { it.groupBy { pkg -> pkg.sourceEditProjectId } },
        message,
    ) { projects, exportsByProject, currentMessage ->
        EditRecordsUiState(
            groupedProjects = EditFeatureCards.associateWith { card ->
                projects.filter { it.type in card.recordOperations }.map { project ->
                    EditProjectRecord(project, exportsByProject[project.id].orEmpty())
                }
            },
            isLoading = false,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EditRecordsUiState(),
    )

    fun deleteAll(ids: List<String>, deleteFiles: Boolean) {
        if (ids.isEmpty()) return
        val exportsByProject = uiState.value.groupedProjects.values
            .flatten()
            .associate { it.project.id to it.exports }
        viewModelScope.launch {
            var failed = 0
            ids.forEach { id ->
                runCatching {
                    if (deleteFiles) {
                        exportsByProject[id].orEmpty().forEach { pkg ->
                            deleteExportFile(pkg.outputPath)
                            exportPackageRepository.delete(pkg.sourceEditProjectId, pkg.outputPath)
                        }
                    }
                    editProjectRepository.delete(id)
                }.onFailure { failed++ }
            }
            message.value = if (failed == 0) {
                "已删除 ${ids.size} 条记录"
            } else {
                "已删除 ${ids.size - failed} 条，$failed 条失败"
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    companion object {
        fun factory(
            editProjectRepository: EditProjectRepository,
            exportPackageRepository: ExportPackageRepository,
            deleteExportFile: (String) -> Boolean = { false },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(EditRecordsViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return EditRecordsViewModel(
                    editProjectRepository,
                    exportPackageRepository,
                    deleteExportFile,
                ) as T
            }
        }
    }
}

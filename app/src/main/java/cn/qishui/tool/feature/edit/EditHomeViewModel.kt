package cn.qishui.tool.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.domain.EditProjectRepository
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.model.EditProject
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.feature.edit.export.MAX_JOIN_SOURCES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecentEdit(
    val project: EditProject,
    val trackTitle: String,
)

data class EditHomeUiState(
    val tracks: List<SourceTrack> = emptyList(),
    val selectedTrackIds: List<String> = emptyList(),
    val recentEdits: List<RecentEdit> = emptyList(),
    val isImporting: Boolean = false,
    val message: String? = null,
) {
    val primaryTrackId: String?
        get() = selectedTrackIds.firstOrNull()

    val selectionLabel: String
        get() = if (selectedTrackIds.isEmpty()) {
            "未选择"
        } else {
            selectedTrackIds.mapIndexed { index, _ -> "#${index + 1}" }.joinToString(" ")
        }
}

class EditHomeViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val editProjectRepository: EditProjectRepository,
) : ViewModel() {
    private val local = MutableStateFlow(LocalState())

    val uiState: StateFlow<EditHomeUiState> = combine(
        sourceTrackRepository.observeAll(),
        editProjectRepository.observeAll(),
        local,
    ) { tracks, projects, current ->
        val available = current.selectedTrackIds.filter { id -> tracks.any { it.id == id } }
        val titles = tracks.associate { it.id to (it.title ?: "未命名") }
        EditHomeUiState(
            tracks = tracks,
            selectedTrackIds = available.ifEmpty { tracks.take(1).map { it.id } },
            recentEdits = projects
                .sortedByDescending { it.updatedAtEpochMillis }
                .take(RECENT_EDIT_LIMIT)
                .map { RecentEdit(it, titles[it.sourceTrackId] ?: "源歌曲不可用") },
            isImporting = current.isImporting,
            message = current.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = EditHomeUiState(),
    )

    fun select(trackId: String) {
        local.value = local.value.copy(selectedTrackIds = listOf(trackId), message = null)
    }

    fun toggleSelection(trackId: String) {
        val current = uiState.value.selectedTrackIds
        val next = if (trackId in current) {
            current - trackId
        } else {
            if (current.size >= MAX_JOIN_SOURCES) {
                local.value = local.value.copy(message = "最多只能选择 $MAX_JOIN_SOURCES 首歌曲")
                return
            }
            current + trackId
        }
        local.value = local.value.copy(selectedTrackIds = next, message = null)
    }

    fun importLocalAudio(uri: String) {
        if (local.value.isImporting) return
        local.value = local.value.copy(isImporting = true, message = null)
        viewModelScope.launch {
            val result = sourceTrackRepository.importLocalAudio(uri)
            local.value = local.value.copy(
                isImporting = false,
                selectedTrackIds = listOfNotNull(result.getOrNull()?.id),
                message = result.fold(
                    onSuccess = { "已导入 ${it.title ?: "本地音频"}" },
                    onFailure = { "导入失败：${it.message ?: "未知错误"}" },
                ),
            )
        }
    }

    fun consumeMessage() {
        local.value = local.value.copy(message = null)
    }

    /** 目录里还没有工作台的功能先如实说明，不假装能打开。 */
    fun notifyPending(label: String) {
        local.value = local.value.copy(message = "「$label」还在开发中")
    }

    private data class LocalState(
        val selectedTrackIds: List<String> = emptyList(),
        val isImporting: Boolean = false,
        val message: String? = null,
    )

    companion object {
        const val RECENT_EDIT_LIMIT = 3

        fun factory(
            sourceTrackRepository: SourceTrackRepository,
            editProjectRepository: EditProjectRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(EditHomeViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return EditHomeViewModel(sourceTrackRepository, editProjectRepository) as T
            }
        }
    }
}

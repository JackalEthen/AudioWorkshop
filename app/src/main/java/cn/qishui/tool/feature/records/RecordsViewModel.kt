package cn.qishui.tool.feature.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.domain.DownloadRepository
import cn.qishui.tool.domain.ParseRecordRepository
import cn.qishui.tool.domain.model.DownloadTask
import cn.qishui.tool.domain.model.ParseRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecordsUiState(
    val downloads: List<DownloadTask> = emptyList(),
    val parseRecords: List<ParseRecord> = emptyList(),
    val busyIds: Set<String> = emptySet(),
    val message: String? = null,
)

class RecordsViewModel(
    private val downloadRepository: DownloadRepository,
    parseRecordRepository: ParseRecordRepository,
) : ViewModel() {
    private val busyIds = MutableStateFlow<Set<String>>(emptySet())
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RecordsUiState> = combine(
        downloadRepository.observeDownloads(),
        parseRecordRepository.observeParseRecords(),
        busyIds,
        message,
    ) { downloads, records, busy, currentMessage ->
        RecordsUiState(downloads, records, busy, currentMessage)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordsUiState(),
    )

    fun pause(id: String) = runOperation(id, "已暂停") { downloadRepository.pause(id) }
    fun resume(id: String) = runOperation(id, "已继续") { downloadRepository.resume(id) }
    fun retry(id: String) = runOperation(id, "已重试") { downloadRepository.retry(id) }

    fun deleteAll(ids: List<String>, deleteFile: Boolean) {
        if (ids.isEmpty()) return
        val scope = ids.filterNot { it in busyIds.value }
        if (scope.isEmpty()) return
        busyIds.value += scope
        viewModelScope.launch {
            var failed = 0
            scope.forEach { id ->
                downloadRepository.delete(id, deleteFile).onFailure { failed++ }
                busyIds.value -= id
            }
            message.value = if (failed == 0) {
                "已删除 ${scope.size} 条记录"
            } else {
                "已删除 ${scope.size - failed} 条，$failed 条失败"
            }
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun runOperation(
        id: String,
        successMessage: String,
        operation: suspend () -> Result<Unit>,
    ) {
        if (id in busyIds.value) return
        busyIds.value += id
        viewModelScope.launch {
            val result = operation()
            message.value = result.fold(
                onSuccess = { successMessage },
                onFailure = { "操作失败：${it.message ?: "未知错误"}" },
            )
            busyIds.value -= id
        }
    }

    companion object {
        fun factory(
            downloadRepository: DownloadRepository,
            parseRecordRepository: ParseRecordRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(RecordsViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return RecordsViewModel(downloadRepository, parseRecordRepository) as T
            }
        }
    }
}

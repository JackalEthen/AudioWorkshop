package cn.music.audioworkshop.feature.settings.cache

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.cache.CacheCleaner
import cn.music.audioworkshop.data.encoder.EncoderClient
import cn.music.audioworkshop.data.encoder.EncoderState
import cn.music.audioworkshop.domain.cache.CacheCategory
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CacheUiState(
    /** 每个分类的占用字节数。 */
    val sizes: Map<CacheCategory, Long> = emptyMap(),
    val totalBytes: Long = 0L,
    val isScanning: Boolean = false,
    val busyCategory: CacheCategory? = null,
    val message: String? = null,
) {
    val isBusy: Boolean
        get() = isScanning || busyCategory != null

    /** 导出进行中时禁止清理：中途删掉临时文件会让导出失败并留下残骸。 */
    fun canClean(isExporting: Boolean): Boolean = !isBusy && !isExporting

    fun sizeOf(category: CacheCategory): Long = sizes[category] ?: 0L
}

/** 缓存清理。统计与删除都放 IO 线程，目录大时不会卡住界面。 */
class CacheViewModel(
    private val cleaner: CacheCleaner,
    private val encoderClient: EncoderClient,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CacheUiState())
    val uiState: StateFlow<CacheUiState> = mutableState.asStateFlow()

    private val mutableExporting = MutableStateFlow(false)

    /** 导出进行中不允许清理，跟着编码器状态走。 */
    val isExporting: StateFlow<Boolean> = mutableExporting.asStateFlow()

    init {
        // 导出进行中不允许清理，跟着这个状态走
        viewModelScope.launch {
            encoderClient.state.collect { state ->
                mutableExporting.value = state is EncoderState.InProgress
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(isScanning = true) }
            val sizes = withContext(Dispatchers.IO) {
                CacheCategory.entries.associateWith { cleaner.sizeOf(it) }
            }
            val total = withContext(Dispatchers.IO) { cleaner.totalSize() }
            mutableState.update { it.copy(sizes = sizes, totalBytes = total, isScanning = false) }
        }
    }

    fun clean(category: CacheCategory) {
        if (isExporting.value) {
            notify("导出进行中，清理会导致导出失败")
            return
        }
        if (mutableState.value.busyCategory != null) return
        viewModelScope.launch {
            mutableState.update { it.copy(busyCategory = category) }
            val freed = withContext(Dispatchers.IO) { cleaner.clear(category) }
            val size = withContext(Dispatchers.IO) { cleaner.sizeOf(category) }
            val total = withContext(Dispatchers.IO) { cleaner.totalSize() }
            mutableState.update {
                it.copy(sizes = it.sizes + (category to size), totalBytes = total, busyCategory = null)
            }
            notify(
                if (freed <= 0L) "${category.label}本来就是空的"
                else "已释放 ${formatBytes(freed)}",
            )
        }
    }

    fun cleanAll() {
        if (isExporting.value) {
            notify("导出进行中，清理会导致导出失败")
            return
        }
        if (mutableState.value.busyCategory != null) return
        viewModelScope.launch {
            mutableState.update { it.copy(busyCategory = CacheCategory.EDIT) }
            val freed = withContext(Dispatchers.IO) {
                CacheCategory.entries.sumOf { cleaner.clear(it) }
            }
            val sizes = withContext(Dispatchers.IO) {
                CacheCategory.entries.associateWith { cleaner.sizeOf(it) }
            }
            val total = withContext(Dispatchers.IO) { cleaner.totalSize() }
            mutableState.update { it.copy(sizes = sizes, totalBytes = total, busyCategory = null) }
            notify(
                if (freed <= 0L) "没有可清理的缓存"
                else "已释放 ${formatBytes(freed)}",
            )
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    private fun notify(message: String) = mutableState.update { it.copy(message = message) }

    companion object {
        fun factory(
            cleaner: CacheCleaner,
            encoderClient: EncoderClient,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CacheViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return CacheViewModel(cleaner, encoderClient) as T
            }
        }
    }
}

/** 字节数显示。低于 1KB 显示「0 B」，不做无意义的小数。 */
fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

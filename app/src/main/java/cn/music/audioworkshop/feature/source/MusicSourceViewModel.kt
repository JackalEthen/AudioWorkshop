package cn.music.audioworkshop.feature.source

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.source.MusicSourceRepository
import cn.music.audioworkshop.domain.source.LxSourceState
import cn.music.audioworkshop.domain.source.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 日志标签。音源的问题只能靠它定位，UI 上的提示太短。 */
private const val TAG = "MusicSourceVM"

/**
 * 音源管理页的状态。
 *
 * 列表和引擎状态是分开的两个流：列表来自 Room（持久），引擎状态来自 QuickJS 运行时。
 * 勾选一行会同时改这两边，所以 UI 上可能出现「勾着但报错」——
 * 那是真实状态，不该藏起来。
 */
class MusicSourceViewModel(
    private val repository: MusicSourceRepository,
    private val engineState: StateFlow<LxSourceState>,
    private val contentResolver: android.content.ContentResolver,
) : ViewModel() {

    val sources: StateFlow<List<MusicSource>> = repository.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engine: StateFlow<LxSourceState> = engineState

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    /** 从链接导入。 */
    fun importFromUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            mutableMessage.value = "请填写脚本链接"
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            mutableMessage.value = "链接要以 http:// 或 https:// 开头"
            return
        }
        launchBusy("正在下载脚本…") {
            repository.importFromUrl(trimmed)
                .onSuccess {
                    mutableMessage.value = "已导入「${it.name}」，勾选后生效"
                }
                .onFailure {
                    android.util.Log.e(TAG, "importFromUrl 失败", it)
                    mutableMessage.value = "导入失败：${describe(it)}"
                }
        }
    }

    /** 从文件导入。 */
    fun importFromFile(uri: Uri, displayName: String) {
        launchBusy("正在读取脚本…") {
            // 已经在 IO 线程了，launchBusy 统一切过
            val content = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("打不开这个文件")
            }.getOrElse { throw IllegalStateException("读取文件失败：${it.message}", it) }
            repository.importFromFile(content, displayName)
                .onSuccess { mutableMessage.value = "已导入「${it.name}」，勾选后生效" }
                .onFailure { mutableMessage.value = "导入失败：${describe(it)}" }
        }
    }

    /**
     * 跑一段耗时操作。
     *
     * 切到 [Dispatchers.IO] 是必须的：里面要做同步 HTTP 和 Room 写入，
     * 而 [viewModelScope] 默认是 Main.immediate，直接跑会被系统抛
     * NetworkOnMainThreadException。
     */
    private fun launchBusy(hint: String, block: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        mutableMessage.value = hint
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } finally {
                mutableBusy.value = false
            }
        }
    }

    /**
     * 把异常翻译成人能看懂的话。
     *
     * [NetworkOnMainThreadException] 这类异常的 message 是 null，
     * 直接显示「未知错误」等于没给信息。
     */
    private fun describe(error: Throwable): String = when (error) {
        is java.net.UnknownHostException -> "连不上这个网址，检查网络或链接是否正确"
        is java.net.SocketTimeoutException -> "请求超时，链接可能太慢"
        is javax.net.ssl.SSLException -> "HTTPS 证书校验失败：${error.message ?: "未知原因"}"
        is java.io.InterruptedIOException -> "网络中断：${error.message ?: "连接被中断"}"
        is android.os.NetworkOnMainThreadException -> "内部错误：网络请求跑在了主线程"
        else -> error.message?.takeIf { it.isNotBlank() }
            ?: "${error.javaClass.simpleName}（没有附带说明）"
    }

    /** 复选框。勾上就换引擎（起 QuickJS 上下文，必须离开主线程）。 */
    fun setSelected(source: MusicSource, selected: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setSelected(source, selected) }
            val error = (engine.value as? LxSourceState.Failed)?.message
            mutableMessage.value = error?.let { "启用失败：$it" }
                ?: if (selected) "已启用「${source.name}」" else "已停用「${source.name}」"
        }
    }

    fun delete(source: MusicSource) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.delete(source.id) }
            mutableMessage.value = "已删除「${source.name}」"
        }
    }

    /** 重新跑一遍选中的音源。 */
    fun reload() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.reloadSelected() }
            val error = (engine.value as? LxSourceState.Failed)?.message
            mutableMessage.value = error?.let { "仍然失败：$it" } ?: "音源已重新加载"
        }
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    companion object {
        fun factory(
            repository: MusicSourceRepository,
            engineState: StateFlow<LxSourceState>,
            contentResolver: android.content.ContentResolver,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MusicSourceViewModel(repository, engineState, contentResolver) as T
        }
    }
}

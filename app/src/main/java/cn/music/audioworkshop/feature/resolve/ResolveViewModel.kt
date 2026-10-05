package cn.music.audioworkshop.feature.resolve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.domain.AudioPlayer
import cn.music.audioworkshop.domain.DownloadRepository
import cn.music.audioworkshop.domain.MusicResolver
import cn.music.audioworkshop.domain.ParseRecordRepository
import cn.music.audioworkshop.domain.PlaybackSnapshot
import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.model.ResolvedTrack
import cn.music.audioworkshop.domain.parse.ParseApiSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ResolveUiState {
    data object Idle : ResolveUiState
    data object Loading : ResolveUiState
    data class Success(
        val track: ResolvedTrack,
        val isEnqueueing: Boolean = false,
        val message: String? = null,
    ) : ResolveUiState
    data class Error(val message: String) : ResolveUiState
}

class ResolveViewModel(
    private val musicResolver: MusicResolver,
    private val parseApiSourceRepository: ParseApiSourceRepository,
    private val downloadRepository: DownloadRepository,
    private val parseRecordRepository: ParseRecordRepository,
    private val audioPlayer: AudioPlayer,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ResolveUiState>(ResolveUiState.Idle)
    val uiState: StateFlow<ResolveUiState> = _uiState.asStateFlow()
    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    /** 设置页里启用中的源，供输入栏下拉选择。 */
    val sources: StateFlow<List<ParseApiSource>> =
        parseApiSourceRepository.observeEnabled()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val selectedSourceId = MutableStateFlow<String?>(null)

    /** 当前选中的源；没选时回落到列表第一个。null 表示一个源都没配。 */
    private val selectedSource: ParseApiSource?
        get() {
            val list = sources.value
            if (list.isEmpty()) return null
            val id = selectedSourceId.value
            return list.firstOrNull { it.id == id } ?: list.first()
        }

    fun selectSource(id: String?) {
        selectedSourceId.value = id
    }

    fun resolve(shareInput: String) {
        if (shareInput.isBlank() || _uiState.value is ResolveUiState.Loading) return
        _uiState.value = ResolveUiState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                musicResolver.resolve(shareInput, selectedSource)
            }
            _uiState.value = result.fold(
                onSuccess = { track ->
                    val recordResult = withContext(Dispatchers.IO) {
                        parseRecordRepository.save(track)
                    }
                    ResolveUiState.Success(
                        track = track,
                        message = if (recordResult.isFailure) "解析成功，但保存历史失败" else null,
                    )
                },
                onFailure = { ResolveUiState.Error(it.message ?: "解析失败，请稍后重试") },
            )
        }
    }

    /**
     * 把解析结果的媒体加入下载中心。
     *
     * 图文源一次返回多张图，逐个入队，单个失败不影响其余 ——
     * 用户要的是「一次全下」，不是「第一个失败就停」。
     */
    fun enqueueDownload() {
        val current = _uiState.value as? ResolveUiState.Success ?: return
        if (current.isEnqueueing) return
        val urls = current.track.mediaUrls.ifEmpty { listOfNotNull(current.track.audioUrl) }
        if (urls.isEmpty()) return
        _uiState.value = current.copy(isEnqueueing = true, message = null)
        viewModelScope.launch {
            var succeeded = 0
            var lastError: String? = null
            for (url in urls) {
                val result = downloadRepository.enqueue(current.track.copy(audioUrl = url))
                result.fold(
                    onSuccess = { succeeded++ },
                    onFailure = { lastError = it.message },
                )
            }
            _uiState.value = current.copy(
                isEnqueueing = false,
                message = when {
                    succeeded == 0 -> "加入下载失败：${lastError ?: "未知错误"}"
                    succeeded < urls.size -> "已加入 $succeeded/${urls.size} 个，其余失败：${lastError ?: "未知错误"}"
                    else -> "已加入下载中心（$succeeded 个）"
                },
            )
        }
    }

    fun togglePreview() {
        val track = (_uiState.value as? ResolveUiState.Success)?.track ?: return
        val url = track.audioUrl?.takeIf(String::isNotBlank) ?: return
        val snapshot = audioPlayer.snapshot.value
        if (snapshot.mediaId == url) {
            if (snapshot.isPlaying) audioPlayer.pause() else audioPlayer.play()
        } else {
            audioPlayer.loadContentUri(url)
            audioPlayer.play()
        }
    }

    fun seekPreview(positionMs: Long) {
        val snapshot = audioPlayer.snapshot.value
        if (snapshot.mediaId == null) return
        audioPlayer.seekTo(positionMs.coerceIn(0L, snapshot.durationMs.coerceAtLeast(0L)))
    }

    fun notify(message: String) {
        _uiState.update { current ->
            if (current is ResolveUiState.Success) current.copy(message = message) else current
        }
    }

    fun consumeMessage() {
        val current = _uiState.value as? ResolveUiState.Success ?: return
        _uiState.value = current.copy(message = null)
    }

    companion object {
        fun factory(
            musicResolver: MusicResolver,
            parseApiSourceRepository: ParseApiSourceRepository,
            downloadRepository: DownloadRepository,
            parseRecordRepository: ParseRecordRepository,
            audioPlayer: AudioPlayer,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (!modelClass.isAssignableFrom(ResolveViewModel::class.java)) {
                    throw IllegalArgumentException("Unsupported ViewModel class: ${modelClass.name}")
                }
                @Suppress("UNCHECKED_CAST")
                return ResolveViewModel(
                    musicResolver = musicResolver,
                    parseApiSourceRepository = parseApiSourceRepository,
                    downloadRepository = downloadRepository,
                    parseRecordRepository = parseRecordRepository,
                    audioPlayer = audioPlayer,
                ) as T
            }
        }
    }
}


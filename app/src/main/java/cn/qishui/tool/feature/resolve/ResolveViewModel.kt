package cn.qishui.tool.feature.resolve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.domain.AudioPlayer
import cn.qishui.tool.domain.DownloadRepository
import cn.qishui.tool.domain.MusicResolver
import cn.qishui.tool.domain.ParseRecordRepository
import cn.qishui.tool.domain.PlaybackSnapshot
import cn.qishui.tool.domain.model.ResolvedTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val downloadRepository: DownloadRepository,
    private val parseRecordRepository: ParseRecordRepository,
    private val audioPlayer: AudioPlayer,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ResolveUiState>(ResolveUiState.Idle)
    val uiState: StateFlow<ResolveUiState> = _uiState.asStateFlow()
    val playback: StateFlow<PlaybackSnapshot> = audioPlayer.snapshot

    fun resolve(shareInput: String) {
        if (shareInput.isBlank() || _uiState.value is ResolveUiState.Loading) return
        _uiState.value = ResolveUiState.Loading
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                musicResolver.resolve(shareInput)
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

    fun enqueueDownload() {
        val current = _uiState.value as? ResolveUiState.Success ?: return
        if (current.isEnqueueing) return
        _uiState.value = current.copy(isEnqueueing = true, message = null)
        viewModelScope.launch {
            val result = downloadRepository.enqueue(current.track)
            _uiState.value = current.copy(
                isEnqueueing = false,
                message = result.fold(
                    onSuccess = { "已加入下载中心" },
                    onFailure = { "加入下载失败：${it.message ?: "未知错误"}" },
                ),
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
                    downloadRepository = downloadRepository,
                    parseRecordRepository = parseRecordRepository,
                    audioPlayer = audioPlayer,
                ) as T
            }
        }
    }
}

package cn.qishui.tool.domain

import kotlinx.coroutines.flow.StateFlow

data class PlaybackSnapshot(
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

interface AudioPlayer {
    val snapshot: StateFlow<PlaybackSnapshot>
    fun loadFile(filePath: String)
    fun loadContentUri(uri: String)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun release()
}

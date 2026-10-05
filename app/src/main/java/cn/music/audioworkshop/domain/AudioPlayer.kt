package cn.music.audioworkshop.domain

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

    /**
     * 永久释放，不可逆。
     *
     * 实现是全应用共享的单例，所以**功能页离开时不能调它**，要调 [pause]。
     * release 之后这个实例就报废了：再 loadFile / play 都不会有任何反应。
     * 只在应用真正结束时才释放。
     */
    fun release()
}

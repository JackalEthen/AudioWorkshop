package cn.qishui.tool.data.media

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import cn.qishui.tool.domain.AudioPlayer
import cn.qishui.tool.domain.PlaybackSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Media3AudioPlayer(context: Context) : AudioPlayer {
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    private var ticker: Job? = null

    override val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    mutableSnapshot.update { it.copy(isPlaying = isPlaying) }
                    if (isPlaying) startTicker() else stopTicker()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    mutableSnapshot.update { it.copy(durationMs = currentDuration()) }
                }
            },
        )
    }

    override fun loadFile(filePath: String) {
        val file = File(filePath)
        if (!file.isFile) return
        prepare(MediaItem.fromUri(file.toUri()), filePath)
    }

    override fun loadContentUri(uri: String) {
        prepare(MediaItem.fromUri(uri.toUri()), uri)
    }

    override fun play() {
        if (player.mediaItemCount == 0) return
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (player.mediaItemCount == 0) return
        player.seekTo(positionMs.coerceAtLeast(0L))
        mutableSnapshot.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    override fun release() {
        stopTicker()
        scope.cancel()
        player.release()
    }

    private fun prepare(item: MediaItem, mediaId: String) {
        stopTicker()
        player.setMediaItem(item)
        player.prepare()
        mutableSnapshot.value = PlaybackSnapshot(mediaId = mediaId)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                mutableSnapshot.update {
                    it.copy(positionMs = currentPosition(), durationMs = currentDuration())
                }
                delay(PositionIntervalMs)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        if (player.mediaItemCount > 0) {
            mutableSnapshot.update { it.copy(isPlaying = false, positionMs = currentPosition()) }
        }
    }

    private fun currentPosition(): Long =
        runCatching { player.currentPosition }.getOrNull()?.coerceAtLeast(0L) ?: 0L

    private fun currentDuration(): Long =
        runCatching { player.duration }.getOrNull()?.takeIf { it > 0L } ?: 0L

    private companion object {
        const val PositionIntervalMs = 200L
    }
}

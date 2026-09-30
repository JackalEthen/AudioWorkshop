package cn.qishui.tool.data.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import cn.qishui.tool.domain.model.SourceOrigin
import cn.qishui.tool.domain.model.LyricsTrack
import cn.qishui.tool.domain.player.PlaybackQuality
import cn.qishui.tool.domain.player.QueueItem
import cn.qishui.tool.domain.player.QueueMaxSize
import cn.qishui.tool.domain.player.RepeatMode
import cn.qishui.tool.domain.player.SoundEffectCommand
import cn.qishui.tool.domain.player.SoundEffectPreset
import cn.qishui.tool.domain.player.UrlResolver
import cn.qishui.tool.domain.player.queueInsertIndex
import cn.qishui.tool.service.PlaybackService
import cn.qishui.tool.util.SourceLog

/**
 * 播放器控制端。连上 [PlaybackService]，把服务里的播放状态转成 UI 能订阅的 [StateFlow]。
 *
 * 队列项靠 MediaItem 本身携带（歌名歌手塞在 MediaMetadata 里），
 * 不走 Binder 传大对象，也不留跨进程静态 Map。
 */
class PlaybackConnection(
    private val context: Context,
    private val resolvers: Map<String, UrlResolver> = mapOf(LocalResolver.SOURCE_LOCAL to LocalResolver()),
) {
    private val controllerFuture = MediaController.Builder(
        context.applicationContext,
        SessionToken(context.applicationContext, ComponentName(context.applicationContext, PlaybackService::class.java)),
    ).buildAsync()

    private val mutableQueue = MutableStateFlow<List<QueueItem>>(emptyList())
    private val mutableState = MutableStateFlow(PlaybackUiState())
    private val mutableSpeed = MutableStateFlow(1.0f)
    private val mutableSoundEffect = MutableStateFlow(SoundEffectPreset.NONE)

    /** 只跑进度轮询，挂在主线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 播放速率，循环切档用。 */
    val playbackSpeed: StateFlow<Float> = mutableSpeed.asStateFlow()

    /** 当前音效预设。 */
    val soundEffect: StateFlow<SoundEffectPreset> = mutableSoundEffect.asStateFlow()

    /** 队列全量，供列表页和队列面板渲染。 */
    val queue: StateFlow<List<QueueItem>> = mutableQueue.asStateFlow()

    /** 播放状态，供 MiniPlayer 和全屏播放页渲染。 */
    val state: StateFlow<PlaybackUiState> = mutableState.asStateFlow()

    val repeatMode: StateFlow<RepeatMode> = MutableStateFlow(RepeatMode.OFF).also { flow ->
        flow.value = RepeatMode.OFF
    }

    private var repeat = RepeatMode.OFF

    init {
        controllerFuture.addListener(
            {
                val controller = controllerFuture.get()
                mutableState.value = PlaybackUiState(
                    isPlaying = controller.isPlaying,
                    currentIndex = controller.currentMediaItemIndex,
                    positionMs = controller.currentPosition.coerceAtLeast(0L),
                    durationMs = controller.duration.coerceAtLeast(0L),
                    repeatMode = repeat,
                    isConnected = true,
                )
                controller.addListener(syncListener)
                rebuildQueue(controller)
                // 服务进程被系统杀掉重启后，处理器会回到「无」。
                // 连上就重推一次，避免 UI 显示教堂但实际是原声。
                sendSoundEffect(mutableSoundEffect.value)
                scope.launch { tickPosition() }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private val syncListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFrom(player)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controllerFuture.get()?.let { rebuildQueue(it) }
        }
    }

    private fun syncFrom(player: Player) {
        mutableState.value = mutableState.value.copy(
            isPlaying = player.isPlaying,
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            repeatMode = repeat,
        )
    }

    /**
     * 按固定间隔推进播放位置。
     *
     * **进度条、时钟、歌词高亮全都只认 [PlaybackUiState.positionMs]**，
     * 而 Media3 的 `onEvents` 只在离散事件触发（播放/暂停、切歌、状态变化），
     * 播放头前进**不会**通知。不自己轮询的话，进度会一直停在起播那一刻，
     * 只有暂停时才跳到真实位置 —— 表现就是「进度条和歌词卡住，暂停一下才同步，
     * 一播放又卡」。
     *
     * 位置没变就不发新状态，否则暂停时每 200ms 都会白白重组一次。
     */
    private suspend fun tickPosition() {
        while (currentCoroutineContext().isActive) {
            if (controllerFuture.isDone) {
                val controller = controllerFuture.get()
                val position = controller.currentPosition.coerceAtLeast(0L)
                if (shouldPublishPosition(position, mutableState.value.positionMs)) {
                    mutableState.value = mutableState.value.copy(
                        positionMs = position,
                        durationMs = controller.duration.coerceAtLeast(0L),
                    )
                }
            }
            delay(POSITION_TICK_MS)
        }
    }

    private fun rebuildQueue(controller: MediaController) {
        mutableQueue.value = (0 until controller.mediaItemCount).map { index ->
            controller.getMediaItemAt(index).toQueueItem()
        }
    }

    /**
     * 插到当前曲的下一位，但不跳过去播。队列为空时直接作为第一项。
     */
    fun playNext(song: QueueItem, quality: PlaybackQuality, resolved: Uri) {
        val controller = controllerFuture.get()
        attachLyrics(song.mediaId, song.lyrics)
        controller.addMediaItem(
            queueInsertIndex(controller.currentMediaItemIndex, controller.mediaItemCount),
            song.toMediaItem(resolved),
        )
        trimOverflow(controller)
    }

    /** 插到当前曲的下一位，并立即跳过去播。 */
    fun playNow(song: QueueItem, quality: PlaybackQuality, resolved: Uri) {
        val controller = controllerFuture.get()
        // 记下真正送进播放器的 URI。URL 是音源给的，但播放失败时需要确认
        // 它有没有在这一步被改写/截断。
        SourceLog.i("PlaybackConn", "playNow ${song.title} -> $resolved")
        attachLyrics(song.mediaId, song.lyrics)
        val index = queueInsertIndex(controller.currentMediaItemIndex, controller.mediaItemCount)
        controller.addMediaItem(index, song.toMediaItem(resolved))
        trimOverflow(controller)
        controller.seekTo(index, 0L)
        controller.play()
    }

    /**
     * 歌词按 mediaId 存在这里。
     *
     * **不能靠队列回读带回来** —— 队列是从服务的 `MediaItem` 重建的，
     * 歌词没地方放（塞 extras 太大、还要跨进程序列化）。
     *
     * 用 StateFlow 而不是 Map：歌词是**播放之后**异步补上的（抓歌词要走网络，
     * 不能让它卡在起播前面），抓到了界面要自己刷新。
     */
    private val mutableLyrics = MutableStateFlow<Map<String, LyricsTrack>>(emptyMap())

    /** 播放页按 mediaId 取歌词。 */
    val lyrics: StateFlow<Map<String, LyricsTrack>> = mutableLyrics.asStateFlow()

    /** 歌词抓到了，补进缓存。播放页会立刻收到。 */
    fun attachLyrics(mediaId: String, track: LyricsTrack) {
        if (track.lines.isEmpty()) return
        mutableLyrics.value = mutableLyrics.value + (mediaId to track)
    }

    /** 队列超上限时从最早的项开始丢，但绝不丢当前正在播的。 */
    private fun trimOverflow(controller: MediaController) {
        var overflow = controller.mediaItemCount - QueueMaxSize
        if (overflow <= 0) return
        val current = controller.currentMediaItemIndex
        var index = 0
        while (overflow > 0 && index < controller.mediaItemCount) {
            if (index != current) {
                controller.removeMediaItem(index)
                overflow--
            } else {
                index++
            }
        }
    }

    /** 队列是攒出来的，必须能一键清空。 */
    fun clear() {
        controllerFuture.get().clearMediaItems()
        mutableQueue.value = emptyList()
    }

    /** 跳到队列里的第 index 首并播放。 */
    fun playQueueAt(index: Int) {
        val controller = controllerFuture.get()
        if (index !in 0 until controller.mediaItemCount) return
        controller.seekTo(index, 0L)
        controller.play()
    }

    fun togglePlay() {
        val controller = controllerFuture.get()
        if (controller.isPlaying) controller.pause() else controller.play()
    }

    fun pause() {
        controllerFuture.get().pause()
    }

    fun next() {
        controllerFuture.get().seekToNextMediaItem()
    }

    fun previous() {
        controllerFuture.get().seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controllerFuture.get().seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 顺序 → 全部 → 单曲 → 顺序 */
    fun cycleRepeatMode() {
        repeat = repeat.next()
        val controller = controllerFuture.get()
        when (repeat) {
            RepeatMode.OFF -> {
                controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.shuffleModeEnabled = false
            }
            RepeatMode.ALL -> {
                controller.repeatMode = Player.REPEAT_MODE_ALL
                controller.shuffleModeEnabled = false
            }
            RepeatMode.ONE -> {
                controller.repeatMode = Player.REPEAT_MODE_ONE
                controller.shuffleModeEnabled = false
            }
        }
        mutableState.value = mutableState.value.copy(repeatMode = repeat)
    }

    fun release() {
        controllerFuture.get().removeListener(syncListener)
        MediaController.releaseFuture(controllerFuture)
    }

    /** 切播放速率。在 [SpeedSteps] 里循环一圈。 */
    fun cyclePlaybackSpeed() {
        val steps = SpeedSteps
        val index = steps.indexOfFirst { kotlin.math.abs(it - mutableSpeed.value) < 0.01f }
        // 走到最后一档要绕回第一档。以前用 coerceAtMost 会卡在 2.0x 回不到 1.0x
        val next = steps[(index + 1).mod(steps.size)]
        setPlaybackSpeed(next)
    }

    fun setPlaybackSpeed(speed: Float) {
        controllerFuture.get().setPlaybackSpeed(speed)
        mutableSpeed.value = speed
    }

    /** 切音效预设。走自定义命令，因为处理器长在服务进程的播放管线里。 */
    fun setSoundEffect(preset: SoundEffectPreset) {
        mutableSoundEffect.value = preset
        sendSoundEffect(preset)
    }

    private fun sendSoundEffect(preset: SoundEffectPreset) {
        controllerFuture.get().sendCustomCommand(
            SoundEffectCommand.SET_SOUND_EFFECT,
            SoundEffectCommand.argsOf(preset),
        )
    }
}

/** 播放速率档位。1.0 是常速。 */
val SpeedSteps = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/** MiniPlayer 和全屏播放页要的全部字段，一份就够，不用到处读 Player。 */
data class PlaybackUiState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
) {
    val hasCurrentSong: Boolean
        get() = currentIndex >= 0
}

internal fun QueueItem.toMediaItem(uri: Uri): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(coverUri?.let(Uri::parse))
        .setIsPlayable(true)
        .build()
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}

/**
 * 轮询播放位置的间隔。
 *
 * 歌词是按行高亮的，间隔太大会明显落后于声音；200ms 是进度条跟手、
 * 歌词不迟滞之间的常规取值，再密只是白烧重组。
 */
internal const val POSITION_TICK_MS = 200L

/** 位置变了才发新状态，避免暂停时空转。 */
internal fun shouldPublishPosition(currentMs: Long, previousMs: Long): Boolean =
    currentMs != previousMs

/** 从服务里读回来的队列项，够渲染列表就行。 */
internal fun MediaItem.toQueueItem(): QueueItem = QueueItem(
    mediaId = mediaId,
    title = mediaMetadata.title?.toString() ?: "未知歌曲",
    artist = mediaMetadata.artist?.toString(),
    album = mediaMetadata.albumTitle?.toString(),
    uri = localConfiguration?.uri?.toString().orEmpty(),
    coverUri = mediaMetadata.artworkUri?.toString(),
    origin = SourceOrigin.LOCAL_IMPORT,
    // 歌词不能塞进 MediaItem 的 extras 传（体积大、还要序列化），
    // 由播放页从 ViewModel 侧的队列缓存读，不走这条回读路径。
)

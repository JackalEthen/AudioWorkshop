package cn.music.audioworkshop.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.data.local.FavoritesDao
import cn.music.audioworkshop.data.local.FavoriteEntity
import cn.music.audioworkshop.data.media.PlaybackConnection
import cn.music.audioworkshop.domain.SourceTrackRepository
import cn.music.audioworkshop.data.lyrics.LyricsRepository
import cn.music.audioworkshop.domain.model.LyricsTrack
import cn.music.audioworkshop.domain.model.SourceCode
import cn.music.audioworkshop.domain.player.PlaybackQuality
import cn.music.audioworkshop.domain.player.QueueItem
import cn.music.audioworkshop.domain.player.SoundEffectPreset
import cn.music.audioworkshop.domain.player.UrlResolveException
import cn.music.audioworkshop.domain.player.UrlResolver
import cn.music.audioworkshop.media.source.LxSourceRequestException
import cn.music.audioworkshop.util.SourceLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 收藏筛选开关的状态，不入库，点一下就切。 */
enum class LibraryFilter { ALL, FAVORITE }

/** 播放器外观开关。 */
data class PlayerAppearance(
    val particleCoverEnabled: Boolean = false,
    val lyricDragEnabled: Boolean = false,
)

/**
 * 播放器首页：歌曲列表 + 收藏筛选 + 播放意图。
 *
 * 播放队列和播放状态由 [PlaybackConnection] 持有，这里只发指令。
 */
class PlayerHomeViewModel(
    private val sourceTrackRepository: SourceTrackRepository,
    private val favoritesDao: FavoritesDao,
    val playback: PlaybackConnection,
    private val resolvers: Map<String, UrlResolver>,
    private val lyricsRepository: LyricsRepository,
    /** 歌曲列表页传 [LibraryFilter.ALL]，收藏页传 [LibraryFilter.FAVORITE]。 */
    private val filter: LibraryFilter = LibraryFilter.ALL,
) : ViewModel() {

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    private val mutableBusySongId = MutableStateFlow<String?>(null)

    /** 列表按收藏筛选后的结果。收藏 id 变了列表自动跟着变。 */
    val songs: StateFlow<List<SourceTrackRow>> = combine(
        sourceTrackRepository.observeAll(),
        favoritesDao.observeFavoriteIds(),
    ) { tracks, favoriteIds ->
        val favoriteSet = favoriteIds.toSet()
        tracks
            .filter { filter != LibraryFilter.FAVORITE || it.id in favoriteSet }
            .map { it.toRow(it.id in favoriteSet) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun isFavorite(songId: String): Boolean =
        songs.value.firstOrNull { it.id == songId }?.isFavorite == true

    fun toggleFavorite(songId: String) {
        viewModelScope.launch {
            if (isFavorite(songId)) {
                favoritesDao.remove(songId)
            } else {
                favoritesDao.add(FavoriteEntity(song_id = songId, created_at = System.currentTimeMillis()))
            }
        }
    }

    /**
     * 直接点击：插到当前曲的下一位，并立即跳过去播。
     * 见 PLAYER_PLAN.md 第 2 节。
     */
    fun playNow(songId: String) = enqueue(songId, playImmediately = true)

    /** 长按「下一首播放」：插到下一位，不打断当前播放。 */
    fun playNext(songId: String) = enqueue(songId, playImmediately = false)

    private fun enqueue(songId: String, playImmediately: Boolean) {
        val song = songs.value.firstOrNull { it.id == songId } ?: return
        if (mutableBusySongId.value != null) return
        mutableBusySongId.value = songId
        viewModelScope.launch {
            val item = song.toQueueItem()
            runCatching {
                val resolver = resolvers[song.sourceCode]
                    ?: resolvers[SourceCode.LOCAL]
                    ?: throw UrlResolveException("没有可用的音源：${song.sourceCode}")
                SourceLog.i("HomePlay", "曲库播放 ${song.title} source=${song.sourceCode} localPath=${song.localPath}")
                val uri = resolver.resolve(item, PlaybackQuality.DEFAULT)
                SourceLog.i("HomePlay", "曲库播放地址已解析：${song.title} -> $uri")
                // 先播，歌词后补。
                if (playImmediately) {
                    playback.playNow(item, PlaybackQuality.DEFAULT, uri)
                } else {
                    playback.playNext(item, PlaybackQuality.DEFAULT, uri)
                }
                viewModelScope.launch {
                    val lyrics = if (song.localPath.isNullOrBlank()) {
                        lyricsRepository.load(
                            platform = song.sourceCode,
                            platformSongId = song.platformSongId,
                            title = song.title,
                            artist = song.artist,
                            durationMs = song.durationMs,
                        )
                    } else {
                        LyricsTrack.EMPTY
                    }
                    playback.attachLyrics(item.mediaId, lyrics)
                }
            }.onFailure { error ->
                // LxSourceRequestException 带的是音源给的原因，别用统一文案盖掉。
                mutableMessage.value = when (error) {
                    is UrlResolveException, is LxSourceRequestException -> error.message
                    else -> "无法播放「${song.title}」：${error.message ?: "未知错误"}"
                }
            }
            mutableBusySongId.value = null
        }
    }

    /** 列表页的导入按钮走文件选择器，和编辑页同一个入口。 */
    fun onImported(uri: String) {
        viewModelScope.launch {
            sourceTrackRepository.importLocalAudio(uri).fold(
                onSuccess = { track ->
                    mutableMessage.value = "已导入 ${track.title ?: "本地音频"}"
                },
                onFailure = { error ->
                    mutableMessage.value = "导入失败：${error.message ?: "未知错误"}"
                },
            )
        }
    }

    /** 长按菜单的「删除」：从库里移除这一首。收藏标记一并清掉，避免留下悬空收藏。 */
    fun delete(songId: String) {
        viewModelScope.launch {
            runCatching {
                if (isFavorite(songId)) favoritesDao.remove(songId)
                sourceTrackRepository.delete(songId)
            }.onSuccess {
                mutableMessage.value = "已删除"
            }.onFailure { error ->
                mutableMessage.value = "删除失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun clearQueue() = playback.clear()

    /** 播放队列里的第 index 首。队列面板点行用。 */
    fun playQueueAt(index: Int) = playback.playQueueAt(index)

    fun consumeMessage() {
        mutableMessage.value = null
    }

    fun togglePlay() = playback.togglePlay()

    fun next() = playback.next()

    fun previous() = playback.previous()

    fun seekTo(positionMs: Long) = playback.seekTo(positionMs)

    fun cycleRepeatMode() = playback.cycleRepeatMode()

    fun cyclePlaybackSpeed() = playback.cyclePlaybackSpeed()

    // ---- 定时关闭 ----

    private var sleepTimerJob: Job? = null
    private val mutableSleepRemainingMs = MutableStateFlow(0L)
    val sleepRemainingMs: StateFlow<Long> = mutableSleepRemainingMs.asStateFlow()

    /** 设置定时关闭。传 0 取消。 */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (minutes <= 0) {
            mutableSleepRemainingMs.value = 0L
            return
        }
        mutableSleepRemainingMs.value = minutes * 60_000L
        sleepTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val left = mutableSleepRemainingMs.value - 1000L
                mutableSleepRemainingMs.value = left.coerceAtLeast(0L)
                if (left <= 0L) {
                    playback.pause()
                    mutableMessage.value = "定时关闭，已停止播放"
                    break
                }
            }
        }
    }

    // ---- 外观开关（设置面板用，阶段 3 才会真正生效） ----

    private val mutableAppearance = MutableStateFlow(PlayerAppearance())
    val appearance: StateFlow<PlayerAppearance> = mutableAppearance.asStateFlow()

    fun setParticleCoverEnabled(enabled: Boolean) {
        mutableAppearance.update { it.copy(particleCoverEnabled = enabled) }
    }

    fun setLyricDragEnabled(enabled: Boolean) {
        mutableAppearance.update { it.copy(lyricDragEnabled = enabled) }
    }

    // ---- 音效 ----

    /** 音效预设。跟播放服务同步，服务重启后连接时会自动重推。 */
    val soundEffect: StateFlow<SoundEffectPreset> = playback.soundEffect

    fun setSoundEffect(preset: SoundEffectPreset) = playback.setSoundEffect(preset)

    companion object {
        fun factory(
            sourceTrackRepository: SourceTrackRepository,
            favoritesDao: FavoritesDao,
            playback: PlaybackConnection,
            resolvers: Map<String, UrlResolver>,
            lyricsRepository: LyricsRepository,
            filter: LibraryFilter = LibraryFilter.ALL,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerHomeViewModel(
                sourceTrackRepository = sourceTrackRepository,
                favoritesDao = favoritesDao,
                playback = playback,
                resolvers = resolvers,
                lyricsRepository = lyricsRepository,
                filter = filter,
            ) as T
        }
    }
}

/** 列表行用的数据。刻意不直接暴露 SourceTrack，UI 层不该碰实体。 */
data class SourceTrackRow(
    val id: String,
    val title: String,
    val artist: String?,
    val coverUri: String?,
    val durationMs: Long?,
    val localPath: String?,
    val sourceCode: String,
    val isFavorite: Boolean,
    /** 平台歌曲 id，向音源求直链必需。本地导入为 null。 */
    val platformSongId: String? = null,
)

internal fun cn.music.audioworkshop.domain.model.SourceTrack.toRow(isFavorite: Boolean) = SourceTrackRow(
    id = id,
    title = title ?: "未命名",
    artist = artist,
    coverUri = coverUri,
    durationMs = durationMs,
    localPath = localPath,
    sourceCode = sourceCode ?: SourceCode.LOCAL,
    isFavorite = isFavorite,
    platformSongId = platformSongId,
)

internal fun SourceTrackRow.toQueueItem() = QueueItem(
    mediaId = id,
    title = title,
    artist = artist,
    uri = localPath.orEmpty(),
    coverUri = coverUri,
    durationMs = durationMs ?: 0L,
    localPath = localPath,
    // 本地导入的保持 LOCAL_IMPORT，搜索入库的走 REMOTE_SOURCE。
    origin = if (localPath.isNullOrBlank()) {
        cn.music.audioworkshop.domain.model.SourceOrigin.REMOTE_SOURCE
    } else {
        cn.music.audioworkshop.domain.model.SourceOrigin.LOCAL_IMPORT
    },
    sourceCode = sourceCode,
    platformSongId = platformSongId,
)

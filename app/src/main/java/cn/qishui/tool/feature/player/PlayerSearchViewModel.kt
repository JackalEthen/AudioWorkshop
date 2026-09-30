package cn.qishui.tool.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.qishui.tool.data.lyrics.LyricsRepository
import cn.qishui.tool.data.media.PlaybackConnection
import cn.qishui.tool.data.search.MusicSearchRepository
import cn.qishui.tool.data.source.LxSourceRuntime
import cn.qishui.tool.domain.SourceTrackRepository
import cn.qishui.tool.domain.model.SourceCode
import cn.qishui.tool.domain.model.SourceOrigin
import cn.qishui.tool.domain.model.SourceTrack
import cn.qishui.tool.domain.source.LxSourceInfo
import cn.qishui.tool.domain.source.LxSourceState
import cn.qishui.tool.domain.player.PlaybackQuality
import cn.qishui.tool.domain.player.QueueItem
import cn.qishui.tool.domain.player.UrlResolveException
import cn.qishui.tool.domain.player.UrlResolver
import cn.qishui.tool.domain.search.SearchHit
import cn.qishui.tool.media.source.LxSourceRequestException
import cn.qishui.tool.util.SourceLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 搜索页。
 *
 * 两种行为刻意分开：
 * - **点行** → 现求直链直接播，不写库
 * - **点「+」** → 存成 SourceTrack 进曲库，之后能从库里播
 *
 * 直链必须过 [UrlResolver]（也就是 lx 音源）—— lx 协议没有内置搜索以外的
 * 播放能力，平台接口只给歌 id，地址得问脚本要。
 */
class PlayerSearchViewModel(
    private val searchRepository: MusicSearchRepository,
    private val sourceTrackRepository: SourceTrackRepository,
    val playback: PlaybackConnection,
    private val resolvers: Map<String, UrlResolver>,
    private val lyricsRepository: LyricsRepository,
    private val lxSourceRuntime: LxSourceRuntime,
) : ViewModel() {

    private val mutableQuery = MutableStateFlow("")
    val query: StateFlow<String> = mutableQuery.asStateFlow()

    private val mutablePlatform = MutableStateFlow(MusicSearchRepository.ALL)
    val platform: StateFlow<String> = mutablePlatform.asStateFlow()

    private val mutableResults = MutableStateFlow<List<SearchHit>>(emptyList())
    val results: StateFlow<List<SearchHit>> = mutableResults.asStateFlow()

    private val mutableSearched = MutableStateFlow(false)
    /** 还没搜过时给一个引导文案，搜过但空要显示「没搜到」，两者不能混。 */
    val searched: StateFlow<Boolean> = mutableSearched.asStateFlow()

    private val mutableLoading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()

    private val mutableBusyHitId = MutableStateFlow<String?>(null)
    val busyHitId: StateFlow<String?> = mutableBusyHitId.asStateFlow()

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    /** 当前音源的状态，搜索页据此决定要不要提示去设置里勾音源。 */
    val sourceState: StateFlow<LxSourceState> = lxSourceRuntime.state

    /**
     * 可选平台 = 「我们能搜的」∩「当前音源能求直链的」。
     *
     * 音源能力从握手时的 `init` 消息拿到（`LxSourceInfo.platforms`），
     * 音源换了这里跟着变 —— 换源不用重启页面。
     *
     * 音源没就绪时退回全量，否则用户只能看到空列表，完全不知道该去设置里勾音源。
     */
    val platforms: StateFlow<List<String>> =
        combine(lxSourceRuntime.state, mutablePlatform) { state, selected ->
            val supported = (state as? LxSourceState.Ready)?.info?.platforms
            val searchable = searchablePlatforms(state)
            // 当前选中的平台被音源换掉了，要跟着落到第一个可用平台上
            if (selected != MusicSearchRepository.ALL && selected !in searchable) {
                mutablePlatform.value = searchable.first()
            }
            searchable
        }.stateIn(viewModelScope, SharingStarted.Eagerly, searchablePlatforms(null))

    private var searchJob: Job? = null

    fun onQueryChange(value: String) {
        mutableQuery.value = value
    }

    fun onPlatformChange(value: String) {
        if (mutablePlatform.value == value) return
        mutablePlatform.value = value
        // 换平台立刻重搜，用户不该再按一次搜索。
        if (mutableQuery.value.isNotBlank()) search()
    }

    fun search() {
        val keyword = mutableQuery.value.trim()
        if (keyword.isEmpty()) {
            mutableResults.value = emptyList()
            mutableSearched.value = false
            return
        }
        // 连点搜索时丢掉上一次的结果，别让慢的盖掉快的。
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableLoading.value = true
            runCatching { searchRepository.search(mutablePlatform.value, keyword, 1, PAGE_SIZE) }
                .onSuccess { result ->
                    mutableResults.value = result.hits
                    mutableSearched.value = true
                }
                .onFailure { error ->
                    mutableResults.value = emptyList()
                    mutableSearched.value = true
                    mutableMessage.value = "搜索失败：${error.message ?: "未知错误"}"
                }
            mutableLoading.value = false
        }
    }

    /** 点行：求直链直接播，不落库。 */
    fun play(hit: SearchHit) {
        if (mutableBusyHitId.value != null) return
        mutableBusyHitId.value = hit.trackId
        viewModelScope.launch {
            runCatching {
                val item = hit.toQueueItem()
                val resolver = resolvers[hit.platform]
                    ?: throw UrlResolveException("没有可用的解析器：${hit.platform}")
                SourceLog.i(TAG, "搜索播放 ${hit.title} platform=${hit.platform} id=${hit.platformSongId}")
                val uri = resolver.resolve(item, PlaybackQuality.DEFAULT)
                SourceLog.i(TAG, "搜索播放地址已解析：${hit.title} -> $uri")
                // 先播，歌词后补。抓歌词要发搜索请求 + 取歌词，
                // 卡在起播前面会让点击到出声延迟十几秒。
                playback.playNow(item, PlaybackQuality.DEFAULT, uri)
                viewModelScope.launch {
                    val lyrics = lyricsRepository.load(
                        platform = hit.platform,
                        platformSongId = hit.platformSongId,
                        title = hit.title,
                        artist = hit.artist,
                        durationMs = hit.durationMs,
                    )
                    playback.attachLyrics(item.mediaId, lyrics)
                }
            }.onFailure { error ->
                SourceLog.e(TAG, "搜索播放失败：${hit.title}", error)
                // LxSourceRequestException 的消息是音源给的原文（网络不通、接口 500、
                // 歌 id 不支持…），比统一换成「无法播放」有用得多，别覆盖。
                mutableMessage.value = when (error) {
                    is UrlResolveException, is LxSourceRequestException -> error.message
                    else -> "无法播放「${hit.title}」：${error.message ?: "未知错误"}"
                }
            }
            mutableBusyHitId.value = null
        }
    }

    /** 点「+」：存进曲库。重复点会 upsert 到同一条。 */
    fun addToLibrary(hit: SearchHit) {
        viewModelScope.launch {
            runCatching {
                sourceTrackRepository.upsert(hit.toSourceTrack())
            }.onSuccess {
                mutableMessage.value = "已加入曲库：${hit.title}"
            }.onFailure { error ->
                mutableMessage.value = "加入曲库失败：${error.message ?: "未知错误"}"
            }
        }
    }

    fun consumeMessage() {
        mutableMessage.value = null
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val TAG = "SearchPlay"
    }
}

/**
 * 我们实现了搜索的平台。顺序就是 UI 上的展示顺序。
 *
 * 注意：**音源的 `supportActions` 里没有 `local`**（本地文件不用求直链），
 * 所以 `local` 永远不会出现在搜索标签里。
 */
private val SEARCHABLE_PLATFORMS = listOf(
    SourceCode.KUWO,
    SourceCode.KUGOU,
    SourceCode.TENCENT,
    SourceCode.NETEASE,
    SourceCode.MIGU,
)

/** 搜索结果转队列项。远端来源，[QueueItem.uri] 先留空，播之前才求直链。 */
private fun SearchHit.toQueueItem() = QueueItem(
    mediaId = trackId,
    title = title,
    artist = artist,
    album = album,
    uri = "",
    coverUri = coverUrl,
    durationMs = durationMs ?: 0L,
    localPath = null,
    origin = SourceOrigin.REMOTE_SOURCE,
    sourceCode = platform,
    platformSongId = platformSongId,
)

/** 搜索结果转 SourceTrack。存的是「平台 + 歌 id」，不是音频本身。 */
private fun SearchHit.toSourceTrack() = SourceTrack(
    id = trackId,
    origin = SourceOrigin.REMOTE_SOURCE,
    sourceShareUrl = null,
    title = title,
    artist = artist,
    album = album,
    localPath = null,
    format = null,
    durationMs = durationMs,
    bitrateBps = null,
    sizeBytes = null,
    sampleRateHz = null,
    lyrics = null,
    fileHash = null,
    coverUri = coverUrl,
    sourceCode = platform,
    platformSongId = platformSongId,
)

/**
 * 搜索页的可选平台。
 *
 * 交集逻辑：搜索只能选「我们实现了搜索」的平台，音源只能支持它握手时
 * 声明的平台。交集才是真正能点通到底的平台 —— 只看一边都会给出
 * 点得动但必然失败的选项。
 *
 * @param state 音源状态；传 null 或非 Ready 时不按音源过滤（用户还没勾音源，
 *   此时给全量并由 UI 提示去勾，比给空列表有用）
 */
internal fun searchablePlatforms(
    state: LxSourceState?,
    searchable: List<String> = SEARCHABLE_PLATFORMS,
): List<String> {
    val supported = (state as? LxSourceState.Ready)?.info
        ?.let { info -> info.platforms.filter { info.supports(it, LxSourceInfo.ACTION_MUSIC_URL) } }
        ?: return searchable
    return searchable.filter { it in supported }
}

fun playerSearchViewModelFactory(
    searchRepository: MusicSearchRepository,
    sourceTrackRepository: SourceTrackRepository,
    playback: PlaybackConnection,
    resolvers: Map<String, UrlResolver>,
    lyricsRepository: LyricsRepository,
    lxSourceRuntime: LxSourceRuntime,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PlayerSearchViewModel(
        searchRepository = searchRepository,
        sourceTrackRepository = sourceTrackRepository,
        playback = playback,
        resolvers = resolvers,
        lyricsRepository = lyricsRepository,
        lxSourceRuntime = lxSourceRuntime,
    ) as T
}

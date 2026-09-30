package cn.qishui.tool.feature.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.qishui.tool.app.AppContainer
import cn.qishui.tool.domain.model.LyricsTrack
import cn.qishui.tool.domain.model.SourceCode
import cn.qishui.tool.feature.edit.lyrics.LyricsPanel
import cn.qishui.tool.ui.components.EmptyState
import cn.qishui.tool.ui.components.QishuiTopBar
import cn.qishui.tool.ui.components.SecondaryButton
import cn.qishui.tool.ui.components.SongRow
import cn.qishui.tool.ui.components.TopSnackbarHost

/**
 * 播放器首页：标题 → 横向方块行（mini player / 收藏 / 搜索 / 添加）→ 歌曲列表。
 *
 * 列表贯穿到底，不为底部导航栏留白——底栏是毛玻璃浮层，内容透过去有层次。
 * 见 PLAYER_PLAN.md 第 1 节。
 */
@Composable
fun PlayerHomeScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFavorites: () -> Unit,
    onSheetVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(container) {
        PlayerHomeViewModel.factory(
            sourceTrackRepository = container.sourceTrackRepository,
            favoritesDao = container.favoritesDao,
            playback = container.playbackConnection,
            resolvers = container.urlResolvers,
            lyricsRepository = container.lyricsRepository,
        )
    }
    val viewModel: PlayerHomeViewModel = viewModel(factory = factory)
    val songs by viewModel.songs.collectAsState()
    val message by viewModel.message.collectAsState()
    val playbackState by viewModel.playback.state.collectAsState()
    val queue by viewModel.playback.queue.collectAsState()
    val playbackSpeed by viewModel.playback.playbackSpeed.collectAsState()
    val sleepRemaining by viewModel.sleepRemainingMs.collectAsState()
    val appearance by viewModel.appearance.collectAsState()
val soundEffect by viewModel.soundEffect.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var miniExpanded by remember { mutableStateOf(false) }
    var sheetVisible by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var sleepSheetVisible by remember { mutableStateOf(false) }
    var settingsSheetVisible by remember { mutableStateOf(false) }
    var queueSheetVisible by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<SourceTrackRow?>(null) }

    // 全屏播放页盖在整屏上时，返回键应该先收起它，而不是让 NavController 把播放页弹掉。
    // 不拦的话会直接退回上一个 Tab，而且底栏因为 sheetVisible 没复位就再也不出现。
    BackHandler(enabled = sheetVisible) {
        sheetVisible = false
        onSheetVisibleChange(false)
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.onImported(it.toString()) } }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val current = queue.getOrNull(playbackState.currentIndex)

    var currentIsFavorite by remember(current?.mediaId) { mutableStateOf(false) }
    // 歌词是播放之后异步补上的（抓歌词要走网络，不能卡在起播前面），
    // 所以这里被动等它流过来，不自己发请求。
    val lyricsById by viewModel.playback.lyrics.collectAsState()
    // 本地导入的歌歌词内嵌在文件里，不走网络。
    var embeddedLyrics by remember(current?.mediaId) { mutableStateOf(LyricsTrack.EMPTY) }

    LaunchedEffect(current?.mediaId) {
        currentIsFavorite = songs.any { it.id == current?.mediaId && it.isFavorite }
        val embedded = container.sourceTrackRepository.getById(current?.mediaId.orEmpty())?.lyrics
        embeddedLyrics = if (embedded.isNullOrBlank()) {
            LyricsTrack.EMPTY
        } else {
            container.lyricsParser.parse(embedded)
        }
    }

    // 网络歌词优先，其次文件内嵌的。
    val currentLyrics = lyricsById[current?.mediaId] ?: embeddedLyrics

    Column(modifier = modifier.fillMaxSize()) {
        // 一级 Tab 页没有上一层，不放返回键
        QishuiTopBar(title = "播放器")

        PlayerTileRow(
            state = playbackState,
            currentTitle = current?.title,
            currentArtist = current?.artist,
            currentCoverUri = current?.coverUri,
            miniExpanded = miniExpanded,
            onToggleExpand = { miniExpanded = !miniExpanded },
            onTogglePlay = viewModel::togglePlay,
            onNext = viewModel::next,
            onOpenPlayer = {
                sheetVisible = true
                // 播放页盖住整屏时要把底部导航栏藏掉
                onSheetVisibleChange(true)
            },
            onOpenFavorites = onOpenFavorites,
            onOpenSearch = onOpenSearch,
            onAddSong = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
        )

        if (songs.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EmptyState(
                    text = "还没有歌曲",
                    hint = "用上面的「添加」导入本地音频，或在解析页下载歌曲",
                )
                SecondaryButton(
                    text = "导入音乐",
                    onClick = { importPicker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // 不给底部 contentPadding：列表要能划到底栏下面去
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        title = song.title,
                        artist = song.artist,
                        coverUri = song.coverUri,
                        durationMs = song.durationMs,
                        isPlaying = song.id == current?.mediaId,
                        // 直接点击：插到下一位并立即播
                        onClick = { viewModel.playNow(song.id) },
                        onLongClick = { actionTarget = song },
                    )
                }
            }
        }
    }

    // 全屏播放页覆盖在整页之上，不是新路由；下滑收起
    AnimatedVisibility(
        visible = sheetVisible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        PlayerSheet(
            state = playbackState,
            current = current,
            lyrics = currentLyrics,
            showLyrics = showLyrics,
            isFavorite = currentIsFavorite,
            playbackSpeed = playbackSpeed,
            sleepRemainingMs = sleepRemaining,
            queueSize = queue.size,
            particleCoverEnabled = appearance.particleCoverEnabled,
            onToggleLyrics = { showLyrics = !showLyrics },
            onToggleFavorite = { current?.mediaId?.let(viewModel::toggleFavorite) },
            onDismiss = {
                sheetVisible = false
                onSheetVisibleChange(false)
            },
            onTogglePlay = viewModel::togglePlay,
            onNext = viewModel::next,
            onPrevious = { viewModel.previous() },
            onSeek = { viewModel.seekTo(it) },
            onCycleRepeatMode = { viewModel.cycleRepeatMode() },
            onCyclePlaybackSpeed = { viewModel.cyclePlaybackSpeed() },
            onOpenSleepTimer = { sleepSheetVisible = true },
            onOpenQueue = { queueSheetVisible = true },
            onOpenSettings = { settingsSheetVisible = true },
        )
    }

    TopSnackbarHost(snackbarHostState)

    if (sleepSheetVisible) {
        SleepTimerSheet(
            remainingMs = sleepRemaining,
            onPick = { minutes ->
                viewModel.setSleepTimer(minutes)
                sleepSheetVisible = false
            },
            onDismiss = { sleepSheetVisible = false },
        )
    }

    if (settingsSheetVisible) {
        PlayerSettingsSheet(
            appearance = appearance,
            soundEffect = soundEffect,
            onPickSoundEffect = viewModel::setSoundEffect,
            onToggleParticle = viewModel::setParticleCoverEnabled,
            onToggleLyricDrag = viewModel::setLyricDragEnabled,
            onDismiss = { settingsSheetVisible = false },
        )
    }

    if (queueSheetVisible) {
        QueueSheet(
            queue = queue,
            currentMediaId = current?.mediaId,
            onPlayAt = { index ->
                viewModel.playQueueAt(index)
                queueSheetVisible = false
            },
            onClear = {
                viewModel.clearQueue()
                queueSheetVisible = false
            },
            onDismiss = { queueSheetVisible = false },
        )
    }

    actionTarget?.let { target ->
        SongActionSheet(
            title = target.title,
            subtitle = target.artist,
            actions = listOf(
                SongAction("下一首播放") { viewModel.playNext(target.id) },
                SongAction("立即播放") { viewModel.playNow(target.id) },
                SongAction(if (target.isFavorite) "取消收藏" else "收藏") {
                    viewModel.toggleFavorite(target.id)
                },
                SongAction("删除") { viewModel.delete(target.id) },
            ),
            onDismiss = { actionTarget = null },
        )
    }
}

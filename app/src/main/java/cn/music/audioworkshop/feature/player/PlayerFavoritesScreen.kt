package cn.music.audioworkshop.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import cn.music.audioworkshop.app.AppContainer
import cn.music.audioworkshop.data.media.PlaybackUiState
import cn.music.audioworkshop.ui.components.EmptyState
import cn.music.audioworkshop.ui.components.QishuiTopBar
import cn.music.audioworkshop.ui.components.SongRow
import cn.music.audioworkshop.ui.components.TopSnackbarHost

/**
 * 收藏页：收纳歌曲列表里长按收藏的歌。
 *
 * 收藏没有单独一页之外的位置，所以是独立二级路由；
 * 但行的渲染和点击处理跟歌曲列表共用 [SongRow] 和同一套入队逻辑。
 */
@Composable
fun PlayerFavoritesScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(container) {
        PlayerHomeViewModel.factory(
            sourceTrackRepository = container.sourceTrackRepository,
            favoritesDao = container.favoritesDao,
            playback = container.playbackConnection,
            resolvers = container.urlResolvers,
            lyricsRepository = container.lyricsRepository,
            filter = LibraryFilter.FAVORITE,
        )
    }
    val viewModel: PlayerHomeViewModel = viewModel(factory = factory)
    val songs by viewModel.songs.collectAsState()
    val message by viewModel.message.collectAsState()
    val playbackState: PlaybackUiState by viewModel.playback.state.collectAsState()
    val queue by viewModel.playback.queue.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var actionTarget by remember { mutableStateOf<SourceTrackRow?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val current = queue.getOrNull(playbackState.currentIndex)

    // TopSnackbarHost 内部是 fillMaxSize()，放进 Column 会吃掉全部剩余高度，
    // 把下面的列表和空态挤成 0 高度（整页只剩标题栏）。必须和 Column 平级做叠层。
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            QishuiTopBar(
                title = "收藏的歌曲",
                onBack = onBack,
            )

            if (songs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EmptyState(
                        text = "还没有收藏",
                        hint = "在歌曲列表里长按歌曲可以收藏",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            title = song.title,
                            artist = song.artist,
                            coverUri = song.coverUri,
                            durationMs = song.durationMs,
                            isPlaying = song.id == current?.mediaId,
                            onClick = { viewModel.playNow(song.id) },
                            onLongClick = { actionTarget = song },
                        )
                    }
                }
            }
        }

        TopSnackbarHost(snackbarHostState)
    }

    actionTarget?.let { target ->
        SongActionSheet(
            title = target.title,
            subtitle = target.artist,
            actions = listOf(
                SongAction("下一首播放") { viewModel.playNext(target.id) },
                SongAction("立即播放") { viewModel.playNow(target.id) },
                SongAction("取消收藏") { viewModel.toggleFavorite(target.id) },
            ),
            onDismiss = { actionTarget = null },
        )
    }
}

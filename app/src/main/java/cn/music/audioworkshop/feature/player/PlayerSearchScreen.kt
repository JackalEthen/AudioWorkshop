package cn.music.audioworkshop.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.music.audioworkshop.R
import cn.music.audioworkshop.app.AppContainer
import cn.music.audioworkshop.data.search.MusicSearchRepository
import cn.music.audioworkshop.domain.model.SourceCode
import cn.music.audioworkshop.domain.source.LxSourceState
import cn.music.audioworkshop.domain.search.SearchHit
import cn.music.audioworkshop.ui.components.EmptyState
import cn.music.audioworkshop.ui.components.LucideIcon
import cn.music.audioworkshop.ui.components.QishuiTopBar
import cn.music.audioworkshop.ui.components.SongRow
import cn.music.audioworkshop.ui.components.VinylRecord

/** 播放器的二级路由。 */
object PlayerRoute {
    const val Search = "player/search"
    const val Favorites = "player/favorites"
}

/**
 * 搜索页。
 *
 * 平台接口只给「平台 + 歌 id」，播放地址必须回头问 lx 音源要
 * （lx 协议里没有内置搜索以外的播放能力）。所以点行的路径是：
 * 搜到 → 求直链 → 播。
 *
 * 「+」按钮把结果存进曲库，之后能从曲库直接播。点行本身不落库。
 */
@Composable
fun PlayerSearchScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenSourceSettings: () -> Unit,
    onOpenPlayer: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val factory = remember(container) {
        playerSearchViewModelFactory(
            searchRepository = container.musicSearchRepository,
            sourceTrackRepository = container.sourceTrackRepository,
            playback = container.playbackConnection,
            resolvers = container.urlResolvers,
            lyricsRepository = container.lyricsRepository,
            lxSourceRuntime = container.lxSourceRuntime,
        )
    }
    val playback = container.playbackConnection
    val viewModel: PlayerSearchViewModel = viewModel(factory = factory)

    val query by viewModel.query.collectAsState()
    val platform by viewModel.platform.collectAsState()
    val platforms by viewModel.platforms.collectAsState()
    val results by viewModel.results.collectAsState()
    val searched by viewModel.searched.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val busyHitId by viewModel.busyHitId.collectAsState()
    val message by viewModel.message.collectAsState()
    val sourceState by viewModel.sourceState.collectAsState()

    // 顶栏唱片：正在播就转，没在播就静止；点一下进全屏播放页。
    val playbackState by playback.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val currentCover = queue.getOrNull(playbackState.currentIndex)?.coverUri

    // 音源没就绪时给出明确引导，不然用户只会看到「搜不出来」。
    val needsSource = sourceState !is LxSourceState.Ready
    val sourceName = (sourceState as? LxSourceState.Ready)?.info?.name
        ?: (sourceState as? LxSourceState.Failed)?.message

    val snackbarHostState = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }
    // 新结果进来滚回顶部，否则停在上一轮的深水区。
    LaunchedEffect(results) {
        if (results.isNotEmpty()) listState.scrollToItem(0)
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            QishuiTopBar(
                title = "搜索",
                onBack = onBack,
                actionContent = {
                    VinylRecord(
                        coverUri = currentCover,
                        playing = playbackState.isPlaying,
                        // 有歌就能点进去，没封面只是显示成默认图案
                        enabled = playbackState.hasCurrentSong,
                        onClick = onOpenPlayer,
                    )
                },
            )

            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text("歌名或歌手") },
                singleLine = true,
                enabled = !loading,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        viewModel.search()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )

            PlatformFilter(
                platforms = platforms,
                selected = platform,
                enabled = !loading,
                onSelect = viewModel::onPlatformChange,
                modifier = Modifier.padding(top = 10.dp),
            )

            if (needsSource) {
                SourceHint(
                    sourceName = sourceName,
                    onOpenSettings = onOpenSourceSettings,
                )
            }

            when {
                loading -> LoadingBox()
                results.isEmpty() -> EmptyBox(searched = searched, needsSource = needsSource)
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(results, key = { it.trackId }) { hit ->
                        SearchResultRow(
                            hit = hit,
                            busy = busyHitId == hit.trackId,
                            onClick = { viewModel.play(hit) },
                            onAdd = { viewModel.addToLibrary(hit) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PlatformFilter(
    platforms: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 「全部」不是平台，是个聚合选项，所以单独排。
        FilterChip(
            selected = selected == MusicSearchRepository.ALL,
            enabled = enabled,
            onClick = { onSelect(MusicSearchRepository.ALL) },
            label = { Text("全部") },
            shape = RoundedCornerShape(10.dp),
        )
        for (code in platforms) {
            FilterChip(
                selected = selected == code,
                enabled = enabled,
                onClick = { onSelect(code) },
                label = { Text(platformLabel(code)) },
                shape = RoundedCornerShape(10.dp),
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    hit: SearchHit,
    busy: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    SongRow(
        title = hit.title,
        artist = listOfNotNull(hit.artist, hit.album, platformLabel(hit.platform))
            .joinToString(" · "),
        coverUri = hit.coverUrl,
        durationMs = hit.durationMs,
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                IconButton(onClick = onAdd) {
                    LucideIcon(
                        icon = R.drawable.ic_plus,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyBox(searched: Boolean, needsSource: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            needsSource -> EmptyState(
                text = "还没勾选音源",
                hint = "音源负责把歌名换算成播放地址。到播放设置里勾选一个音源脚本",
            )
            searched -> EmptyState(text = "没搜到歌曲", hint = "换个关键词，或切换平台再试")
            else -> EmptyState(
                text = "搜歌名或歌手",
                hint = "播放地址由右上角播放设置里勾选的音源提供",
            )
        }
    }
}

/** 音源没就绪时的提示条，带一个直达设置的入口。 */
@Composable
private fun SourceHint(sourceName: String?, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (sourceName.isNullOrBlank()) {
                "未勾选音源，搜到的歌播不了"
            } else {
                "当前音源加载失败：$sourceName"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenSettings) {
            Text("去设置")
        }
    }
}

private fun platformLabel(code: String): String = when (code) {
    SourceCode.KUWO -> "酷我"
    SourceCode.KUGOU -> "酷狗"
    SourceCode.TENCENT -> "QQ"
    SourceCode.NETEASE -> "网易"
    SourceCode.MIGU -> "咪咕"
    else -> code
}

package cn.music.audioworkshop.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import cn.music.audioworkshop.R
import cn.music.audioworkshop.data.media.PlaybackUiState
import cn.music.audioworkshop.ui.components.LucideIcon

/** 方块边长。mini player 收起时也是这个尺寸，和另外三个方块对齐。 */
private val TileSize = 64.dp

/** 展开后的长条宽度。够放封面 + 歌名 + 三个按钮（播放/下一首/收起）。 */
private val ExpandedWidth = 320.dp

/** 一行的固定高度：方块 + 间距 + 名称文字。两种形态都用它，切换时高度不跳。 */
private val TileRowHeight = TileSize + 3.dp + 16.dp

private val TileShape = RoundedCornerShape(18.dp)

/**
 * 标题下方那一行：mini player + 收藏 + 搜索 + 添加。
 *
 * 是一条**连续的横向滚动行**，不是分页器，也不是均分布局。
 * 格子宽度固定且紧凑（相邻间距 10dp），两侧留 20dp 边距。
 * 之所以要能滑：mini player 展开成 320dp 长条后会把后面三个方块挤出屏幕，
 * 必须滑过去才看得到；以后往这一行加 EQ、播放模式之类也一样。
 *
 * 收起态总宽 4×64+3×10+40(尾部暗示) = 306dp，比可用宽度略大一点，
 * 所以收起时也能滑，但露出的一点点尾部空白只是「还有更多」的暗示，不占地方。
 */
@Composable
fun PlayerTileRow(
    state: PlaybackUiState,
    currentTitle: String?,
    currentArtist: String?,
    currentCoverUri: String?,
    miniExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenSearch: () -> Unit,
    onAddSong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        // 两侧留边距，内容不顶屏幕边
        contentPadding = PaddingValues(horizontal = 20.dp),
        // 四个格子之间的间距统一，比默认的 10dp 略宽一点
        horizontalArrangement = Arrangement.spacedBy(TileGap),
        verticalAlignment = Alignment.Top,
    ) {
        // mini player 固定在最左边
        item(key = "mini") {
            MiniPlayerTile(
                modifier = Modifier.width(if (miniExpanded) ExpandedWidth else TileSize),
                state = state,
                title = currentTitle,
                artist = currentArtist,
                coverUri = currentCoverUri,
                expanded = miniExpanded,
                onToggleExpand = onToggleExpand,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
                onOpenPlayer = onOpenPlayer,
            )
        }
        item(key = "favorites") {
            SquareTile(
                modifier = Modifier.width(TileSize),
                icon = R.drawable.ic_star,
                label = "收藏",
                onClick = onOpenFavorites,
            )
        }
        item(key = "search") {
            SquareTile(
                modifier = Modifier.width(TileSize),
                icon = R.drawable.ic_search,
                label = "搜索",
                onClick = onOpenSearch,
            )
        }
        item(key = "add") {
            SquareTile(
                modifier = Modifier.width(TileSize),
                icon = R.drawable.ic_plus,
                label = "添加",
                onClick = onAddSong,
            )
        }
        // 尾部一小段空白：让收起态也能滑出一点位移，给出「这行能滑」的暗示
        item(key = "tail") {
            Spacer(modifier = Modifier.width(TailHintWidth))
        }
    }
}

/** 四个格子之间的间距。原来是 10dp，稍微放宽一点。 */
private val TileGap = 16.dp

/** 尾部滑动暗示的宽度。很小，不会在右侧留出一大片空白。 */
private val TailHintWidth = 40.dp

/**
 * 主题色填充的圆角正方形：图标 + 名称。
 * 三个功能方块都用它，永远是正方形，不会变形。
 */
@Composable
private fun SquareTile(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(TileRowHeight)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(TileSize)
                .clip(RoundedCornerShape(18.dp))
                // 主题色填充，跟随主题切换
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            LucideIcon(
                icon = icon,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 26.dp,
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * mini player 方块，两态：
 * - 收起：正方形，只显示封面
 * - 展开：长方形，封面 + 歌名/歌手 + 播放暂停 + 下一首 + 收起键
 */
@Composable
private fun MiniPlayerTile(
    state: PlaybackUiState,
    title: String?,
    artist: String?,
    coverUri: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSong = state.hasCurrentSong
    if (!expanded) {
        Column(
            modifier = modifier
                .height(TileRowHeight)
                .clickable { if (hasSong) onToggleExpand() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CoverBox(
                hasSong = hasSong,
                coverUri = coverUri,
                size = TileSize,
                onClick = { if (hasSong) onToggleExpand() },
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (hasSong) "播放中" else "播放器",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        return
    }

    // 展开态高度锁死，和收起态一样，切形态时这一行不跳
    Column(
        modifier = modifier.height(TileRowHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TileSize)
                .clip(TileShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CoverBox(
                hasSong = hasSong,
                coverUri = coverUri,
                size = 48.dp,
                // 点封面进全屏播放页
                onClick = { if (hasSong) onOpenPlayer() },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasSong) title.orEmpty().ifBlank { "未知歌曲" } else "还没有播放",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (hasSong) artist.orEmpty() else "点添加导入歌曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TileIconButton(
                icon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                enabled = hasSong,
                onClick = onTogglePlay,
            )
            TileIconButton(
                icon = R.drawable.ic_skip_forward,
                enabled = hasSong,
                onClick = onNext,
            )
            // 收起键：把长方形恢复成圆角正方形
            TileIconButton(
                icon = R.drawable.ic_chevron_left,
                enabled = true,
                onClick = onToggleExpand,
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
    }
}

@Composable
private fun CoverBox(
    hasSong: Boolean,
    coverUri: String?,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 没歌时不画封面，避免灰块占位
        if (hasSong && !coverUri.isNullOrBlank()) {
            AsyncImage(model = coverUri, contentDescription = null, modifier = Modifier.size(size))
        } else if (hasSong) {
            LucideIcon(
                icon = R.drawable.ic_music,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = (size.value / 2.4f).dp,
            )
        }
    }
}

@Composable
private fun TileIconButton(
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(
            icon = icon,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
            size = 22.dp,
        )
    }
}

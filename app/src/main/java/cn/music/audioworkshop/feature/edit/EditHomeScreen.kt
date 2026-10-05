package cn.music.audioworkshop.feature.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.model.EditOperation
import cn.music.audioworkshop.ui.components.TopSnackbarHost
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.LucideIcon
import cn.music.audioworkshop.ui.components.QishuiCardShape
import cn.music.audioworkshop.ui.components.QishuiTopBar
import cn.music.audioworkshop.ui.components.ScreenScroll
import cn.music.audioworkshop.ui.components.SecondaryButton
import cn.music.audioworkshop.ui.components.SectionHeader
import cn.music.audioworkshop.ui.components.StatusPill
import cn.music.audioworkshop.ui.components.StatusTone

@Composable
fun EditHomeScreen(
    viewModel: EditHomeViewModel,
    onOpenWorkspace: (EditOperation, String, List<String>) -> Unit,
    onOpenTrim: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenFade: () -> Unit,
    onOpenJoin: () -> Unit,
    onOpenSpeedPitch: () -> Unit,
    onOpenLrc: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenDenoise: () -> Unit,
    onOpenReverb: () -> Unit,
    onOpenEcho: () -> Unit,
    onOpenChoir: () -> Unit,
    onOpenRepair: () -> Unit,
    onOpenVideoAudio: () -> Unit,
    onOpenStereoOrbit: () -> Unit,
    onOpenLoudness: () -> Unit,
    onOpenStereoSplit: () -> Unit,
    onOpenStereoCompose: () -> Unit,
    onOpenConvert: () -> Unit,
    onOpenMetadata: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { TopSnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            QishuiTopBar(title = "编辑")
ScreenScroll(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.padding(top = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 全部平铺，靠组标题分段。以前是三组 tab，
                    // 每次只能看到一组，得来回切才知道有什么功能。
                    FeatureGroup.entries.forEach { group ->
                        val groupFeatures = remember(group) {
                            FeatureCatalog.filter { it.group == group }
                        }
                        if (groupFeatures.isNotEmpty()) {
                            SectionHeader(title = group.label, caption = null)
                            FeatureGridCard(
                                features = groupFeatures,
                                onClick = { feature ->
                                    val operation = feature.operation
                                    when {
                                        operation != null -> onOpenWorkspace(operation, "", emptyList())
                                        feature.tool == "trim" -> onOpenTrim()
                                        feature.tool == "volume" -> onOpenVolume()
                                        feature.tool == "fade" -> onOpenFade()
                                        feature.tool == "join" -> onOpenJoin()
                                        feature.tool == "speed_pitch" -> onOpenSpeedPitch()
                                        feature.tool == "lrc" -> onOpenLrc()
                                        feature.tool == "equalizer" -> onOpenEqualizer()
                                        feature.tool == "denoise" -> onOpenDenoise()
                                        feature.tool == "reverb" -> onOpenReverb()
                                        feature.tool == "echo" -> onOpenEcho()
                                        feature.tool == "choir" -> onOpenChoir()
                                        feature.tool == "repair" -> onOpenRepair()
                                        feature.tool == "stereo_orbit" -> onOpenStereoOrbit()
                                        feature.tool == "loudness" -> onOpenLoudness()
                                        feature.tool == "stereo_split" -> onOpenStereoSplit()
                                        feature.tool == "stereo_compose" -> onOpenStereoCompose()
                                        feature.tool == "video_audio" -> onOpenVideoAudio()
                                        feature.tool == "convert" -> onOpenConvert()
                                        feature.tool == "metadata" -> onOpenMetadata()
                                        else -> viewModel.notifyPending()
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 一张卡片内 4 个功能：图标在上、文字在下，跟参考图排布一致。 */
@Composable
private fun FeatureGridCard(
    features: List<FeatureEntry>,
    onClick: (FeatureEntry) -> Unit,
) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            features.chunked(4).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    row.forEach { feature ->
                        FeatureTile(
                            feature = feature,
                            onClick = { onClick(feature) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(FEATURES_PER_CARD - row.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(
    feature: FeatureEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(88.dp)
            .clip(QishuiCardShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LucideIcon(
            icon = feature.icon,
            tint = MaterialTheme.colorScheme.primary,
            size = 26.dp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = feature.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RecentEditCard(recent: RecentEdit, onContinue: () -> Unit) {
    FloatingCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${recent.trackTitle} · ${recent.project.type.label()}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "保留 ${recent.project.segments.size} 段 · ${formatStamp(recent.project.updatedAtEpochMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(text = "已应用", tone = StatusTone.Active)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    text = "继续编辑",
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun EditOperation.label(): String = when (this) {
    EditOperation.TRIM -> "裁剪"
    EditOperation.JOIN -> "合成"
    EditOperation.FADE_IN -> "淡入"
    EditOperation.FADE_OUT -> "淡出"
    EditOperation.GAIN -> "增益"
    EditOperation.LYRIC_OFFSET -> "歌词校正"
}

private fun formatStamp(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()




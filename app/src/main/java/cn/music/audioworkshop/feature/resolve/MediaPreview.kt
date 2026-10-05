package cn.music.audioworkshop.feature.resolve

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import android.widget.VideoView
import coil.compose.AsyncImage
import cn.music.audioworkshop.domain.model.MediaKind

/**
 * 解析结果的媒体预览。视频和图片走各自的控件，音频仍用播放器进度条。
 *
 * 放在解析页而不是全局：播放器和编辑页只面向音乐，
 * 这里是对外部解析结果做的一次性查看。
 */
@Composable
fun MediaPreview(
    kind: MediaKind,
    url: String,
    modifier: Modifier = Modifier,
) {
    when (kind) {
        MediaKind.VIDEO -> VideoPreview(url = url, modifier = modifier)
        MediaKind.IMAGE -> ImagePreview(url = url, modifier = modifier)
        // 音频由 PlaybackBar 承担，这里不重复渲染
        MediaKind.AUDIO -> Unit
    }
}

/**
 * 视频预览。
 *
 * 固定 16:9 而不是按真实分辨率算比例：竖屏视频塞进 16:9 会被裁掉上下，
 * 按真实比例又会让页面高度随每个视频跳动。
 */
@Composable
private fun VideoPreview(url: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f),
        factory = { context ->
            VideoView(context).apply {
                setMediaController(null)
                setVideoPath(url)
                setOnPreparedListener { player ->
                    player.isLooping = false
                    // 只 setVideoPath 画面会停在第一帧不动 —— 必须显式 start
                    seekTo(1)
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    // 编码不支持或地址失效时不要静默黑屏
                    true
                }
            }
        },
        update = { view ->
            if (view.tag != url) {
                view.tag = url
                view.setVideoPath(url)
                view.seekTo(1)
                view.start()
            }
        },
        onRelease = { view -> runCatching { view.stopPlayback() } },
    )
}

@Composable
private fun ImagePreview(url: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        AsyncImage(
            model = url,
            contentDescription = "解析结果图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 图片加载失败或视频不可播时的说明。 */
@Composable
fun MediaPreviewHint(kind: MediaKind, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${kind.label}预览",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (kind == MediaKind.VIDEO) {
                "若画面空白，多半是该编码本机不支持"
            } else {
                "若加载失败，多半是地址已过期"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

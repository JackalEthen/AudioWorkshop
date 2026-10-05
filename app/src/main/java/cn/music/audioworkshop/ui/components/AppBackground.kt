package cn.music.audioworkshop.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cn.music.audioworkshop.ui.theme.LocalWallpaperBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberWallpaperBitmap(uri: String?, targetWidthPx: Int = 720): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri, targetWidthPx) {
        value = uri
            ?.takeIf(String::isNotBlank)
            ?.let { withContext(Dispatchers.IO) { decodeWallpaper(context, Uri.parse(it), targetWidthPx) } }
    }
    return bitmap
}

@Composable
fun AppBackground(
    wallpaperUri: String?,
    wallpaperAlpha: Float,
    wallpaperBlurDp: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetWidthPx = with(density) { 1080.dp.roundToPx() }
    val wallpaper by produceState<ImageBitmap?>(initialValue = null, wallpaperUri, targetWidthPx) {
        value = wallpaperUri
            ?.takeIf(String::isNotBlank)
            ?.let { uri -> withContext(Dispatchers.IO) { decodeWallpaper(context, Uri.parse(uri), targetWidthPx) } }
    }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        wallpaper?.let { image ->
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(wallpaperAlpha.coerceIn(0f, 1f))
                    .blur(wallpaperBlurDp.dp),
                contentScale = ContentScale.Crop,
            )
            // ponytail: 全局白蒙罩，强度跟着壁纸透明度走，保证标题和正文可读
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = SCRIM_BASE + SCRIM_RANGE * wallpaperAlpha.coerceIn(0f, 1f))),
            )
        }
        CompositionLocalProvider(LocalWallpaperBitmap provides wallpaper) {
            content()
        }
    }
}

// ponytail: 轻量白蒙罩，只压一点对比度，强度跟壁纸透明度走
private const val SCRIM_BASE = 0.04f
private const val SCRIM_RANGE = 0.13f

internal fun decodeWallpaper(
    context: Context,
    uri: Uri,
    targetWidthPx: Int,
): ImageBitmap? = runCatching {
    // ponytail: 只在“显示时”按屏幕宽度降采样（省内存），落盘文件仍是无损 PNG 原图
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    val limit = if (targetWidthPx > 0) targetWidthPx * 3 else 4200
    while (bounds.outWidth / sample > limit && sample < 16) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?.asImageBitmap()
}.onFailure { android.util.Log.w("QishuiWallpaper", "壁纸解码失败: $uri", it) }.getOrNull()

package cn.music.audioworkshop.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

private const val FRAME_RATIO = 9f / 16f

/**
 * 壁纸裁剪：9:16 取景框，双指缩放 + 单指平移，确认后按原图像素裁剪并以 PNG 无损落盘。
 */
@Composable
fun WallpaperCropScreen(
    source: ImageBitmap,
    onCancel: () -> Unit,
    onConfirm: (File) -> Unit,
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var frameWidth by remember { mutableFloatStateOf(0f) }
    var frameHeight by remember { mutableFloatStateOf(0f) }

    val imageWidth = source.width.toFloat()
    val imageHeight = source.height.toFloat()
    val fit = if (frameWidth > 0f && frameHeight > 0f) {
        minOf(frameWidth / imageWidth, frameHeight / imageHeight)
    } else {
        1f
    }
    val total = fit * scale
    val drawnWidth = imageWidth * total
    val drawnHeight = imageHeight * total
    val slackX = maxOf(0f, (drawnWidth - frameWidth) / 2f)
    val slackY = maxOf(0f, (drawnHeight - frameHeight) / 2f)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(FRAME_RATIO)
                .onSizeChanged {
                    frameWidth = it.width.toFloat()
                    frameHeight = it.height.toFloat()
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        val nextTotal = fit * scale
                        val nextSlackX = maxOf(0f, (imageWidth * nextTotal - frameWidth) / 2f)
                        val nextSlackY = maxOf(0f, (imageHeight * nextTotal - frameHeight) / 2f)
                        offsetX = (offsetX + pan.x).coerceIn(-nextSlackX, nextSlackX)
                        offsetY = (offsetY + pan.y).coerceIn(-nextSlackY, nextSlackY)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = source,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
                contentScale = ContentScale.Fit,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "拖动调整位置，双指缩放",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.size(width = 140.dp, height = 52.dp),
                )
                PrimaryButton(
                    text = "确认",
                    onClick = {
                        val bitmap = source.asAndroidBitmap()
                        val cropWidth = (frameWidth / total).roundToInt().coerceIn(1, bitmap.width)
                        val cropHeight = (frameHeight / total).roundToInt().coerceIn(1, bitmap.height)
                        val left = (((frameWidth - drawnWidth) / 2f - offsetX) / total)
                            .roundToInt()
                            .coerceIn(0, (bitmap.width - cropWidth).coerceAtLeast(0))
                        val top = (((frameHeight - drawnHeight) / 2f - offsetY) / total)
                            .roundToInt()
                            .coerceIn(0, (bitmap.height - cropHeight).coerceAtLeast(0))
                        val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
                        onConfirm(saveWallpaper(context, cropped))
                    },
                    modifier = Modifier.size(width = 140.dp, height = 52.dp),
                )
            }
        }
    }
}

private fun saveWallpaper(context: Context, bitmap: Bitmap): File {
    val directory = File(context.filesDir, "wallpapers").apply { mkdirs() }
    val file = File(directory, "wallpaper.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

fun wallpaperUri(file: File): String = Uri.fromFile(file).toString()

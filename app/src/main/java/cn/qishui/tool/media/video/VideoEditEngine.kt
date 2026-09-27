package cn.qishui.tool.media.video

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * 视频处理，全部走 Media3 Transformer（Google 官方库），
 * 编码管线、关键帧、封装都由它负责，这里只做编排。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoEditEngine(context: Context) {

    private val appContext = context.applicationContext

    /** 裁剪：取 [startMs, endMs] 一段。 */
    suspend fun trim(
        source: File,
        startMs: Long,
        endMs: Long,
        target: File,
        scaleHeight: Int,
        onProgress: (Int) -> Unit,
    ): Result<File> {
        val mediaItem = MediaItem.Builder()
            .setUri(source.toURI().toString())
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        val item = EditedMediaItem.Builder(mediaItem).setEffects(scaleEffects(scaleHeight)).build()
        return run(item, null, target, onProgress)
    }

    /** 拼接：按顺序首尾相接。参数不一致时 Transformer 会自动重编码。 */
    suspend fun join(
        sources: List<File>,
        target: File,
        scaleHeight: Int,
        onProgress: (Int) -> Unit,
    ): Result<File> {
        if (sources.size < 2) return Result.failure(IllegalArgumentException("至少选择 2 个视频"))
        val items = sources.map { file ->
            EditedMediaItem.Builder(
                MediaItem.Builder().setUri(file.toURI().toString()).build(),
            ).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build())
            .setEffects(scaleEffects(scaleHeight))
            .build()
        return run(null, composition, target, onProgress)
    }

    /** 变速变调：速度变了但音调不变（走官方 createExperimentalSpeedChangingEffect）。 */
    suspend fun speed(
        source: File,
        speed: Float,
        target: File,
        scaleHeight: Int,
        onProgress: (Int) -> Unit,
    ): Result<File> {
        val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(speed))
        val item = EditedMediaItem.Builder(
            MediaItem.Builder().setUri(source.toURI().toString()).build(),
        ).setEffects(
            Effects(listOf(pair.first), listOf(pair.second) + scaleVideoEffects(scaleHeight)),
        ).build()
        return run(item, null, target, onProgress)
    }

    private fun scaleVideoEffects(scaleHeight: Int): List<androidx.media3.common.Effect> =
        if (scaleHeight <= 0) emptyList() else listOf(Presentation.createForHeight(scaleHeight))

    /** 清晰度：按目标高度缩放，保持原比例。[scaleHeight] <= 0 表示保持原始分辨率。 */
    private fun scaleEffects(scaleHeight: Int): Effects =
        if (scaleHeight <= 0) {
            Effects.EMPTY
        } else {
            Effects(emptyList(), listOf(Presentation.createForHeight(scaleHeight)))
        }

    /** 恒定速度：自己实现，省得依赖工具类的包路径在不同版本里变来变去。 */
    private class ConstantSpeed(private val value: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = value

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = Long.MAX_VALUE
    }

    private suspend fun run(
        item: EditedMediaItem?,
        composition: Composition?,
        target: File,
        onProgress: (Int) -> Unit,
    ): Result<File> {
        target.parentFile?.mkdirs()
        val done = CompletableDeferred<Result<File>>()
        val holder = ProgressHolder()

        val transformer = Transformer.Builder(appContext)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (target.exists() && target.length() > 0L) {
                            done.complete(Result.success(target))
                        } else {
                            done.complete(Result.failure(IllegalStateException("导出完成但没有产出文件")))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        done.complete(
                            Result.failure(
                                IllegalStateException(exportException.message ?: "视频处理失败"),
                            ),
                        )
                    }
                },
            )
            .build()

        val started = runCatching {
            if (composition != null) {
                transformer.start(composition, target.absolutePath)
            } else {
                transformer.start(requireNotNull(item), target.absolutePath)
            }
        }.isSuccess

        if (!started) {
            transformer.cancel()
            return Result.failure(IllegalStateException("无法启动视频处理，请确认文件有效"))
        }

        // Transformer 没有逐帧回调，只能轮询。
        while (!done.isCompleted) {
            val state = transformer.getProgress(holder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(holder.progress.coerceIn(0, 100))
            }
            delay(POLL_INTERVAL_MS)
        }

        val result = done.await()
        onProgress(100)
        return result
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
    }
}

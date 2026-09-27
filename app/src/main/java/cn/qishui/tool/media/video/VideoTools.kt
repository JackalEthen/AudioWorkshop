package cn.qishui.tool.media.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import cn.qishui.tool.media.effect.PcmBuffer
import cn.qishui.tool.media.pcm.PcmChunkReader
import java.io.File
import java.io.FileInputStream

/**
 * 视频相关能力。全部走系统解码器，不引入新的原生依赖。
 * 音视频合成/拼接需要重编码的部分明确不做（AVMUX/JOIN 只在参数一致时零重编码）。
 */
class VideoTools(
    private val context: Context,
    private val reader: PcmChunkReader,
) {

    /** 抽取视频里的音轨，写成 WAV，交给上层当作新歌曲导入。 */
    fun extractAudio(source: File, targetName: String = "视频音频"): Result<File> = runCatching {
        require(source.isFile && source.length() > 0L) { "视频文件不可用" }
        val extractor = MediaExtractor()
        try {
            FileInputStream(source).use { extractor.setDataSource(it.fd) }
            val track = (0 until extractor.trackCount)
                .firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IllegalStateException("这个视频里没有音频轨")
            extractor.selectTrack(track)
            val buffer = PcmBuffer.read(reader.uriSource(Uri.fromFile(source)))
            val target = File(context.cacheDir, "video-audio").apply { mkdirs() }
                .let { File(it, "$targetName.wav") }
            if (target.exists()) target.delete()
            buffer.writeWav(target)
            target
        } finally {
            extractor.release()
        }
    }

    fun durationMsOf(source: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            FileInputStream(source).use { retriever.setDataSource(it.fd) }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(0L)

    fun sizeOf(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: 0L
    }.getOrDefault(0L)

    /** 抽帧成图片目录，供"视频相册"用；返回抽出的张数。 */
    fun extractFrames(source: File, outputDir: File, count: Int, onFrame: (File) -> Unit): Int {
        if (!source.isFile || count <= 0) return 0
        if (!outputDir.exists()) outputDir.mkdirs()
        val retriever = MediaMetadataRetriever()
        return try {
            FileInputStream(source).use { retriever.setDataSource(it.fd) }
            val durationUs = (durationMsOf(source) * 1000L).coerceAtLeast(1L)
            var saved = 0
            for (index in 0 until count) {
                val positionUs = if (count == 1) {
                    durationUs / 2
                } else {
                    durationUs * index / (count - 1)
                }
                val bitmap = retriever.getFrameAtTime(
                    positionUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                ) ?: continue
                val target = File(outputDir, "frame_%03d.jpg".format(index))
                target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
                bitmap.recycle()
                onFrame(target)
                saved++
            }
            saved
        } catch (error: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 两段参数一致的视频零重编码拼接，交给 MediaMuxer 处理。 */
    fun canJoinWithoutReencode(first: File, second: File): Boolean {
        val a = videoFormatOf(first) ?: return false
        val b = videoFormatOf(second) ?: return false
        return a == b
    }

    fun videoFormatOf(source: File): String? = runCatching {
        val extractor = MediaExtractor()
        try {
            FileInputStream(source).use { extractor.setDataSource(it.fd) }
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            listOf(
                format.getString(MediaFormat.KEY_MIME),
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT),
                format.getInteger(MediaFormat.KEY_FRAME_RATE),
            ).joinToString("|")
        } finally {
            extractor.release()
        }
    }.getOrNull()

    fun durationUsOf(source: File): Long = durationMsOf(source) * 1000L
}

internal fun File.writeBytesFrom(buffer: PcmBuffer) {
    buffer.writeWav(this)
}

internal fun copyFile(source: File, target: File) {
    FileInputStream(source).use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
}

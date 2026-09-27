package cn.qishui.tool.data.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream

data class ProbedAudio(
    val format: String?,
    val durationMs: Long?,
    val bitrateBps: Long?,
    val sampleRateHz: Long?,
    val sizeBytes: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val channels: Int = 0,
)

class AudioFileProbe {

    companion object {
        val EMPTY_PROBE = ProbedAudio(null, null, null, null, 0L, null, null, null)
    }

    fun probeFile(file: File): ProbedAudio {
        val sizeBytes = file.length()
        // 传 fd 而不是路径：中文文件名在 native 层会 Failed to instantiate extractor
        return FileInputStream(file).use { stream ->
            val container = probeContainer { it.setDataSource(stream.fd) }
                ?: ContainerProbe(null, null, null, null, 0)
            container.toProbedAudio(sizeBytes, probeTags { it.setDataSource(stream.fd) })
        }
    }

    fun probeUri(context: Context, uri: Uri): ProbedAudio {
        val sizeBytes = querySize(context, uri)
        val container = probeContainer { it.setDataSource(context, uri, null) }
            ?: ContainerProbe(null, null, null, null, 0)
        return container.toProbedAudio(sizeBytes, probeTags { it.setDataSource(context, uri) })
    }

    private fun probeContainer(configure: (MediaExtractor) -> Unit): ContainerProbe? {
        val extractor = MediaExtractor()
        return try {
            configure(extractor)
            val audio = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            audio?.let { format ->
                ContainerProbe(
                    mime = containerNameOf(format.getString(MediaFormat.KEY_MIME)),
                    durationMs = format.longOrNull(MediaFormat.KEY_DURATION)?.div(1000L),
                    bitrateBps = format.longOrNull(MediaFormat.KEY_BIT_RATE),
                    sampleRateHz = format.longOrNull(MediaFormat.KEY_SAMPLE_RATE),
                    channels = format.longOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.toInt() ?: 0,
                )
            }
        } catch (error: Exception) {
            android.util.Log.w("QishuiProbe", "轨道探测失败，已按无元数据处理", error)
            null
        } finally {
            extractor.release()
        }
    }

    private fun probeTags(configure: (MediaMetadataRetriever) -> Unit): TrackTags {
        val retriever = MediaMetadataRetriever()
        return try {
            configure(retriever)
            TrackTags(
                title = retriever.tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            )
        } catch (error: Exception) {
            TrackTags(null, null, null)
        } finally {
            retriever.release()
        }
    }

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: 0L
    }.getOrDefault(0L)

    private fun MediaMetadataRetriever.tag(key: Int): String? =
        extractMetadata(key)?.trim()?.takeIf(String::isNotEmpty)

    // ponytail: MediaExtractor 有些键会缺失或存成字符串，getLong 会抛；逐键兜住，别让一个可选键毁掉整个探测
    private fun MediaFormat.longOrNull(key: String): Long? {
        if (!containsKey(key)) return null
        return runCatching { getLong(key) }.getOrNull()
            ?: runCatching { getString(key)?.trim()?.toLongOrNull() }.getOrNull()
    }

    private fun ContainerProbe.toProbedAudio(sizeBytes: Long, tags: TrackTags) = ProbedAudio(
        format = mime,
        durationMs = durationMs?.takeIf { it > 0L },
        bitrateBps = bitrateBps?.takeIf { it > 0L },
        sampleRateHz = sampleRateHz?.takeIf { it > 0L },
        sizeBytes = sizeBytes,
        title = tags.title,
        artist = tags.artist,
        album = tags.album,
        channels = channels,
    )

    private data class ContainerProbe(
        val mime: String?,
        val durationMs: Long?,
        val bitrateBps: Long?,
        val sampleRateHz: Long?,
        val channels: Int,
    )

    private data class TrackTags(val title: String?, val artist: String?, val album: String?)
}

internal fun containerNameOf(mime: String?): String? = when (mime?.substringAfter('/')?.removePrefix("x-")) {
    "mpeg", "mpeg3" -> "mp3"
    "wav", "vnd.wave", "wave" -> "wav"
    "flac" -> "flac"
    "mp4", "m4a" -> "m4a"
    "aac", "aacp" -> "aac"
    "opus" -> "opus"
    "ogg" -> "ogg"
    else -> mime?.substringAfter('/')?.removePrefix("x-")?.takeIf(String::isNotBlank)
}

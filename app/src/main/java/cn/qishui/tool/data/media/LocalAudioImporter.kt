package cn.qishui.tool.data.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cn.qishui.tool.domain.model.SourceOrigin
import cn.qishui.tool.domain.model.SourceTrack
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalAudioImporter(
    private val context: Context,
    private val probe: AudioFileProbe,
) {
    suspend fun import(uri: Uri): Result<SourceTrack> = withContext(Dispatchers.IO) {
        var target: File? = null
        runCatching {
            val displayName = queryDisplayName(uri)
            val container = containerFromDisplayName(displayName)
                ?: containerNameOf(context.contentResolver.getType(uri))?.takeIf { it in SupportedContainers }
                ?: throw IllegalArgumentException("不支持的音频格式：${displayName.ifBlank { "未知" }}")
            val directory = File(context.filesDir, "imports")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("无法创建导入目录")
            }
            val file = resolveTarget(directory, displayName, container)
            target = file
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
                file.length()
            } ?: throw IllegalStateException("无法读取所选文件")
            if (copied <= 0L) {
                file.delete()
                throw IllegalStateException("复制到的文件是空的")
            }
            // 元数据只是锦上添花：探测失败也要能导入，真实可用性交给解码器判断
            val probed = runCatching { probe.probeFile(file) }
                .onFailure { android.util.Log.w("QishuiProbe", "元数据探测失败，按未知时长导入", it) }
                .getOrDefault(AudioFileProbe.EMPTY_PROBE)
            SourceTrack(
                id = "local:${file.absolutePath}",
                origin = SourceOrigin.LOCAL_IMPORT,
                sourceShareUrl = null,
                title = probed.title ?: displayName.substringBeforeLast('.', displayName).ifBlank { "本地音频" },
                artist = probed.artist,
                album = probed.album,
                localPath = file.absolutePath,
                format = container,
                durationMs = probed.durationMs,
                bitrateBps = probed.bitrateBps,
                sizeBytes = probed.sizeBytes.takeIf { it > 0L } ?: copied,
                sampleRateHz = probed.sampleRateHz,
                lyrics = null,
                fileHash = file.md5Hex(),
                coverUri = null,
            )
        }.onFailure {
            // 失败就把刚复制进来的半成品清掉，别留垃圾文件
            target?.takeIf { it.isFile }?.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }.getOrNull()?.trim().orEmpty()

    private fun containerFromDisplayName(value: String): String? = value
        .substringAfterLast('.', "")
        .lowercase()
        .takeIf { it.isNotBlank() }
        ?.takeIf { it in SupportedContainers }

    private fun resolveTarget(directory: File, displayName: String, container: String): File {
        val base = displayName.substringBeforeLast('.', displayName)
            .replace(InvalidFileNameCharacters, "_")
            .trim()
            .trim('.', ' ')
            .take(80)
            .ifBlank { "import" }
        val target = File(directory, "$base.$container")
        if (!target.exists()) return target
        var index = 1
        while (File(directory, "$base-$index.$container").exists()) index++
        return File(directory, "$base-$index.$container")
    }

    private fun File.md5Hex(): String {
        val digest = MessageDigest.getInstance("MD5")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DefaultBufferSize)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private companion object {
        const val DefaultBufferSize = 64 * 1024
        // opus/ogg/webm 交给系统解码器（MediaExtractor 原生支持 Opus）
        val SupportedContainers = setOf("mp3", "wav", "flac", "m4a", "aac", "opus", "ogg", "webm")
        val InvalidFileNameCharacters = Regex("""[<>:"/\\|?*\u0000-\u001F\u007F]""")
    }
}

package cn.qishui.tool.data.download

import cn.qishui.tool.domain.download.DownloadTarget
import cn.qishui.tool.domain.download.DownloadTargetResolver
import cn.qishui.tool.domain.download.downloadTargetOf
import cn.qishui.tool.domain.model.DownloadTask
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class ResumableDownloader(
    private val client: OkHttpClient,
    private val targetResolver: DownloadTargetResolver,
) {
    suspend fun download(
        task: DownloadTask,
        onProgress: suspend (DownloadTask) -> Unit,
    ): DownloadTask = withContext(Dispatchers.IO) {
        val temporaryUrl = task.temporaryUrl ?: throw DownloadException("下载地址不可用")
        val partTarget = downloadTargetOf(task.partPath ?: error("Download Task 缺少临时文件路径"))
        val finalTarget = downloadTargetOf(task.finalPath ?: error("Download Task 缺少最终文件路径"))
        val partFile = partTarget.appFileOrNull() ?: throw DownloadException("临时文件位置不可用")
        partFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
        if (finalTarget is DownloadTarget.AppFile &&
            finalTarget.appFileOrNull()?.let { matchesExpectedFile(it, task.expectedSizeBytes, task.expectedMd5) } == true
        ) {
            return@withContext task.copy(
                downloadedBytes = task.expectedSizeBytes ?: 0L,
                partPath = null,
                updatedAtEpochMillis = System.currentTimeMillis(),
            )
        }

        var currentTask = task
        val localLength = partFile.takeIf(File::isFile)?.length() ?: 0L
        val validator = task.eTag ?: task.lastModified
        val resume = localLength > 0L && validator != null
        if (localLength > 0L && !resume && partFile.exists() && !partFile.delete()) {
            throw DownloadException("无法清理无校验信息的临时文件")
        }

        val request = Request.Builder()
            .url(temporaryUrl)
            .apply {
                if (resume) {
                    header("Range", "bytes=$localLength-")
                    header("If-Range", checkNotNull(validator))
                }
            }
            .get()
            .build()

        val response = runInterruptible(Dispatchers.IO) {
            client.newCall(request).execute()
        }
        response.use {
            when {
                it.code == 403 || it.code == 410 -> throw DownloadException("下载地址已失效，请重试获取新地址")
                resume && it.code == 416 -> {
                    val remoteLength = parseUnsatisfiedContentLength(it.header("Content-Range"))
                    if (remoteLength == null || remoteLength != localLength) {
                        throw DownloadException("服务器拒绝续传，本地文件长度不匹配")
                    }
                }
                resume && it.code == 206 -> {
                    requireAcceptedContentType(it.header("Content-Type"))
                    if (!canAppendPartialResponse(it.code, localLength, it.header("Content-Range"))) {
                        throw DownloadException("服务器返回了无效的续传范围")
                    }
                    currentTask = currentTask.withResponseValidators(it)
                    writeBody(
                        it,
                        partFile,
                        append = true,
                        initialLength = localLength,
                        task = currentTask,
                        onProgress = onProgress,
                    )
                }
                it.code == 200 -> {
                    requireAcceptedContentType(it.header("Content-Type"))
                    if (partFile.exists() && !partFile.delete()) {
                        throw DownloadException("无法清空临时文件")
                    }
                    currentTask = currentTask.withResponseValidators(it)
                    writeBody(
                        it,
                        partFile,
                        append = false,
                        initialLength = 0L,
                        task = currentTask,
                        onProgress = onProgress,
                    )
                }
                else -> throw DownloadException("下载失败（HTTP ${it.code}）")
            }
        }

        val downloadedLength = partFile.length()
        task.expectedSizeBytes?.let { expectedSize ->
            if (expectedSize != downloadedLength) {
                partFile.delete()
                throw DownloadException("文件大小校验失败")
            }
        }
        val actualMd5 = calculateMd5(partFile)
        task.expectedMd5?.let { expectedMd5 ->
            if (!md5HexMatches(expectedMd5, actualMd5)) {
                partFile.delete()
                throw DownloadException("MD5 校验失败")
            }
        }
        if (targetResolver.exists(finalTarget)) {
            partFile.delete()
            throw DownloadException("目标文件已存在，未覆盖")
        }
        if (!targetResolver.publish(partTarget, finalTarget)) {
            throw DownloadException("下载文件发布失败")
        }
        currentTask.copy(
            downloadedBytes = downloadedLength,
            partPath = null,
            finalPath = finalTarget.stableKey,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private suspend fun writeBody(
        response: Response,
        partFile: File,
        append: Boolean,
        initialLength: Long,
        task: DownloadTask,
        onProgress: suspend (DownloadTask) -> Unit,
    ) {
        val body = response.body ?: throw DownloadException("下载响应没有内容")
        body.byteStream().use { input ->
            FileOutputStream(partFile, append).use { output ->
                val buffer = ByteArray(DefaultBufferSize)
                var position = initialLength
                var lastUpdatedAt = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    position += count
                    val now = System.currentTimeMillis()
                    if (now - lastUpdatedAt >= ProgressIntervalMillis) {
                        onProgress(
                            task.copy(
                                downloadedBytes = position,
                                updatedAtEpochMillis = now,
                            ),
                        )
                        lastUpdatedAt = now
                    }
                }
                onProgress(
                    task.copy(
                        downloadedBytes = position,
                        updatedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    private suspend fun calculateMd5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DefaultBufferSize)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private suspend fun matchesExpectedFile(
        file: File,
        expectedSize: Long?,
        expectedMd5: String?,
    ): Boolean {
        if (!file.isFile) return false
        if (expectedSize == null || expectedMd5 == null) return false
        if (file.length() != expectedSize) return false
        return md5HexMatches(expectedMd5, calculateMd5(file))
    }

    private fun requireAcceptedContentType(value: String?) {
        if (!isAcceptedContentType(value)) {
            throw DownloadException("服务器返回了不支持的内容类型")
        }
    }

    private fun DownloadTask.withResponseValidators(response: Response): DownloadTask = copy(
        eTag = response.header("ETag") ?: eTag,
        lastModified = response.header("Last-Modified") ?: lastModified,
        updatedAtEpochMillis = System.currentTimeMillis(),
    )

    private companion object {
        const val DefaultBufferSize = 64 * 1024
        const val ProgressIntervalMillis = 250L
    }
}

class DownloadException(message: String) : Exception(message)

internal fun DownloadTarget.appFileOrNull(): File? = (this as? DownloadTarget.AppFile)?.let { File(it.absolutePath) }

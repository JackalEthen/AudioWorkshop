package cn.music.audioworkshop.feature.edit.export

import cn.music.audioworkshop.domain.ExportPackageRepository
import cn.music.audioworkshop.domain.download.DownloadTarget
import cn.music.audioworkshop.domain.download.DownloadTargetResolver
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.model.ExportPackage
import cn.music.audioworkshop.domain.model.ExportValidationStatus
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把编码好的临时文件发布到设置里指定的下载目录。
 *
 * **为什么不弹 SAF 选择框。** 输出目录在设置页已经选过了
 * （`AppSettings.downloadDirectoryUri`），每次导出再让用户选一遍既多余
 * 又会出问题：SAF 的 `CreateDocument` 在用户点确定那一刻就把空文档建出来了，
 * 导出失败没人清理，用户目录里就留下一个 0 KB 的文件。
 *
 * 改成直接算目标：先写应用内的 part 文件，再由 [DownloadTargetResolver]
 * 发布到最终位置（SAF 目录树或应用私有目录都行）。
 *
 * **失败必须清干净。** 这正是「导出后目录里多了一堆 0 KB 文件」的来源：
 * 目标已经建出来、数据还没写进去。任何一步失败都要把建出来的文件删掉。
 */
class ExportPublisher(
    private val targetResolver: DownloadTargetResolver,
    /** 落库用的历史记录仓库。为 null 时不记历史（测试用）。 */
    private val packageRepository: ExportPackageRepository? = null,
) {

    /**
     * 一次成功发布的结果。
     *
     * [location] 必须是能直接喂给 `Uri.parse` 的真实位置，不能是文件名 ——
     * 历史记录靠它来打开和删除文件。
     */
    data class PublishedExport(val bytes: Long, val location: String)

    /**
     * 发布成功后要写进历史记录的信息。
     *
     * 落库放在这里而不是编码侧：编码器只知道 cache 里的临时路径，而那个文件
     * 在 publish 开头就被删了。记它等于记一个永远打不开的路径（历史记录会显示
     * PASSED，但点「打开」和「删除」都找不到文件）。
     */
    data class HistoryRecord(
        val editProjectId: String,
        val format: String,
        val durationMs: Long,
    )

    /**
     * 发布 [tempPath] 指向的临时文件，落到 [fileName]。
     *
     * 同名已存在会自动加序号，不覆盖用户已有的文件。
     *
     * @param history 非空时在发布成功后写一条历史记录，[location] 作为落点。
     *   null 表示不记历史。
     * @return 写入字节数和可长期引用的真实位置（SAF 目录是 `content://` URI，
     *   应用私有目录是绝对路径），历史记录要靠它才能打开/删除文件
     */
    suspend fun publish(
        tempPath: String,
        fileName: String,
        history: HistoryRecord? = null,
    ): PublishedExport = withContext(Dispatchers.IO) {
        val source = File(tempPath)
        if (!source.isFile) throw IOException("导出临时文件不存在")

        // ---- 诊断：把整条链路的关键事实一次打全 ----
        android.util.Log.i(
            "QishuiDiag",
            "publish ENTER tempPath=$tempPath size=${source.length()} name=$fileName",
        )

        val final = uniqueTarget(fileName)
        val part = targetResolver.partTarget(UUID.randomUUID().toString())
        val partFile = (part as? DownloadTarget.AppFile)?.let { File(it.absolutePath) }
        // 记下 publish 有没有成功。只有成功写入的文件才允许在异常时清理 ——
        // 否则会把「根本没写出去」的占位也当成残骸去删。
        var written = false

        try {
            if (partFile == null) throw IOException("无法创建导出临时目标")
            partFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
            partFile.outputStream().use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            // 必须在发布之前量：发布完 part 已经被改名或删掉
            val bytes = partFile.length()
            if (bytes <= 0L) throw IOException("导出文件大小为 0")
            android.util.Log.i(
                "QishuiDiag",
                "publish COPIED to part=${partFile.absolutePath} bytes=$bytes " +
                    "final=$final exists=${targetResolver.exists(final)}",
            )
            val publishResult = targetResolver.publish(part, final)
            android.util.Log.i(
                "QishuiDiag",
                "publish TARGET result=$publishResult final=$final " +
                    "existsAfter=${targetResolver.exists(final)}",
            )
            if (!publishResult) {
                throw IOException("写入下载目录失败，可能没有访问权限")
            }
            written = true
            // publish 成功后 part 已被改名/删除，源临时文件也没用了
            source.delete()
            val location = try {
                targetResolver.publishedLocation(final)
            } catch (error: Throwable) {
                // 只影响历史记录里「打开/删除」能不能定位到文件，文件本身已经在目标位置了。
                // 这里绝不能往上抛：抛出去会进下面的 catch，把刚写好的文件删掉 ——
                // SAF 的 findFile() 对 SingleDocumentFile 必抛 UnsupportedOperationException，
                // 真机上表现就是「导出成功但下载目录里什么都没有」。
                android.util.Log.w("qishui/publish", "publishedLocation failed, file is already written", error)
                targetResolver.describe(final)
            }
            recordHistory(history, location, bytes)
            PublishedExport(bytes, location)
        } catch (failure: Throwable) {
            // 关键：只清理没能成功写入目标的位置，不能删已经写好的文件
            if (!written) {
                runCatching { targetResolver.delete(final) }
            }
            runCatching { partFile?.delete() }
            runCatching { source.delete() }
            // message 经常是 null（CancellationException 等），只报「未知错误」没法查
            android.util.Log.e(
                "qishui/publish",
                "publish failed name=$fileName tree=${(final as? DownloadTarget.TreeDocument)?.treeUri} " +
                    "app=${(final as? DownloadTarget.AppFile)?.absolutePath} ex=$failure",
                failure,
            )
            throw failure
        }
    }

    /**
     * 记一条历史，指向 [location] 这个真实落点。
     *
     * 落库失败只记日志：文件已经在目标位置了，为了一条历史记录把导出判成失败
     * 会让用户以为文件没生成，反而去重复导出。
     */
    private suspend fun recordHistory(history: HistoryRecord?, location: String, bytes: Long) {
        val repository = packageRepository ?: return
        val record = history ?: return
        runCatching {
            repository.upsert(
                ExportPackage(
                    sourceEditProjectId = record.editProjectId,
                    outputPath = location,
                    format = record.format,
                    durationMs = record.durationMs,
                    sizeBytes = bytes,
                    createdAt = System.currentTimeMillis(),
                    validationStatus = ExportValidationStatus.PASSED,
                ),
            )
        }.onFailure {
            android.util.Log.e("qishui/publish", "历史记录落库失败 project=${record.editProjectId} loc=$location", it)
        }
    }

    /** 同名加序号，直到目标不存在。 */
    private fun uniqueTarget(fileName: String): DownloadTarget {
        var candidate = targetResolver.finalTarget(fileName)
        if (!targetResolver.exists(candidate)) return candidate
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        val suffix = if (extension.isBlank()) "" else ".$extension"
        var index = 1
        while (index < 1000) {
            candidate = targetResolver.finalTarget("$stem($index)$suffix")
            if (!targetResolver.exists(candidate)) return candidate
            index++
        }
        throw IOException("同名文件太多，无法生成新文件名")
    }
}

/**
 * 从 job + 编码结果拼出历史记录信息。
 *
 * 编码结果不是 [ExportResult.Completed] 时返回 null —— 失败的任务不该进历史。
 * 各功能页的 `onExportResult` 都调它，不自己拼字段。
 */
fun historyOf(job: ExportJob, result: ExportResult): ExportPublisher.HistoryRecord? {
    if (result !is ExportResult.Completed) return null
    return ExportPublisher.HistoryRecord(
        editProjectId = job.editProjectId,
        format = job.format.extension,
        durationMs = result.durationMs,
    )
}

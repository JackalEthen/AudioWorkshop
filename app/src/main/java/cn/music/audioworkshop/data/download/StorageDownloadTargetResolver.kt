package cn.music.audioworkshop.data.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cn.music.audioworkshop.domain.download.DownloadTarget
import cn.music.audioworkshop.domain.download.DownloadTargetResolver
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

class StorageDownloadTargetResolver(
    context: Context,
    private val appDownloadsDirectory: File,
    private val treeUriProvider: () -> String?,
) : DownloadTargetResolver {
    private val applicationContext = context.applicationContext
    private val contentResolver = applicationContext.contentResolver

    override fun partTarget(taskId: String): DownloadTarget =
        DownloadTarget.AppFile(File(appDownloadsDirectory, ".$taskId.part").absolutePath)

    override fun finalTarget(fileName: String): DownloadTarget {
        val treeUri = treeUriProvider()?.takeIf(String::isNotBlank) ?: return appFileTarget(fileName)
        return DownloadTarget.TreeDocument(
            treeUri = treeUri,
            fileName = fileName,
            documentUri = findDocumentUri(treeUri, fileName),
        )
    }

    override fun lengthOf(target: DownloadTarget): Long = when (target) {
        is DownloadTarget.AppFile -> File(target.absolutePath).takeIf(File::isFile)?.length() ?: 0L
        is DownloadTarget.TreeDocument -> 0L
    }

    override fun exists(target: DownloadTarget): Boolean = when (target) {
        is DownloadTarget.AppFile -> File(target.absolutePath).isFile
        is DownloadTarget.TreeDocument -> target.documentUri?.let(::documentOf)?.exists() == true
    }

    override fun delete(target: DownloadTarget): Boolean {
        return when (target) {
            is DownloadTarget.AppFile -> {
                val file = File(target.absolutePath)
                if (!isManagedAppFile(file)) return false
                file.takeIf(File::exists)?.delete() ?: true
            }
            is DownloadTarget.TreeDocument -> {
                target.documentUri?.takeIf(String::isNotBlank)
                    ?.let { createdDocumentCache.remove(createdKey(target.treeUri, target.fileName)) }
                target.documentUri?.let { documentOf(it)?.delete() } ?: true
            }
        }
    }

    override fun publish(part: DownloadTarget, final: DownloadTarget): Boolean {
        val partFile = (part as? DownloadTarget.AppFile)?.let { File(it.absolutePath) } ?: return false
        if (!partFile.isFile) return false
        return when (final) {
            is DownloadTarget.AppFile -> {
                val target = File(final.absolutePath)
                if (target.exists()) return false
                target.parentFile?.takeIf { !it.exists() }?.mkdirs()
                partFile.renameTo(target)
            }

            is DownloadTarget.TreeDocument -> publishToTree(partFile, final)
        }
    }

    override fun describe(target: DownloadTarget): String = when (target) {
        is DownloadTarget.AppFile -> target.absolutePath
        is DownloadTarget.TreeDocument -> "${target.treeUri}|${target.fileName}"
    }

    override fun publishedLocation(target: DownloadTarget): String = when (target) {
        is DownloadTarget.AppFile -> target.absolutePath
        // publish() 把 created.uri 丢了，这里按 treeUri + 文件名再查一次。
        // 查不到就退回 describe()，至少历史里还能看出落在哪棵目录树下。
        is DownloadTarget.TreeDocument ->
            target.documentUri?.takeIf(String::isNotBlank)
                ?: findDocumentUri(target.treeUri, target.fileName)
                ?: describe(target)
    }

    override fun editableCopy(target: DownloadTarget): File? = when (target) {
        is DownloadTarget.AppFile -> File(target.absolutePath).takeIf(File::isFile)
        is DownloadTarget.TreeDocument -> copyOutOfTree(target)
    }

    private fun copyOutOfTree(target: DownloadTarget.TreeDocument): File? {
        val fileName = target.fileName.takeIf(String::isNotBlank) ?: return null
        val documentUri = target.documentUri?.takeIf(String::isNotBlank)
            ?: findDocumentUri(target.treeUri, fileName)
            ?: return null
        appDownloadsDirectory.takeIf { !it.exists() }?.mkdirs()
        val destination = uniqueFileIn(File(appDownloadsDirectory, fileName))
        val copied = runCatching {
            contentResolver.openInputStream(Uri.parse(documentUri))?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }.getOrNull()
        if (copied == null || destination.length() <= 0L) {
            destination.delete()
            return null
        }
        return destination
    }

    private fun uniqueFileIn(candidate: File): File {
        if (!candidate.exists()) return candidate
        val stem = candidate.name.substringBeforeLast('.', candidate.name)
        val extension = candidate.name.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val next = File(candidate.parentFile, "$stem($index)" + if (extension.isBlank()) "" else ".$extension")
            if (!next.exists()) return next
            index++
        }
    }

    private fun appFileTarget(fileName: String): DownloadTarget {
        appDownloadsDirectory.takeIf { !it.exists() }?.mkdirs()
        return DownloadTarget.AppFile(File(appDownloadsDirectory, fileName).absolutePath)
    }

    private fun isManagedAppFile(file: File): Boolean = try {
        val root = appDownloadsDirectory.canonicalFile
        val candidate = file.canonicalFile
        candidate != root && candidate.path.startsWith(root.path + File.separator)
    } catch (error: Exception) {
        false
    }

    private fun publishToTree(partFile: File, final: DownloadTarget.TreeDocument): Boolean {
        val treeUri = final.treeUri.takeIf(String::isNotBlank) ?: return false
        // root 是真正的树根 TreeDocument，可以安全地 findFile / createFile。
        //
        // 以前这里先 findOrCreateDirectory 取子目录，而那个返回值是
        // SingleDocumentFile —— 对它 findFile() 会抛 UnsupportedOperationException，
        // 且 SAF 调用会阻塞 25 秒以上（真机实测）。
        // 另外子目录名一旦被 SAF 改名成「汽水音乐工具 (1)」，
        // 之后按原名 findFile 永远返回 null，于是每次都重新 CREATE、序号不断累加。
        //
        // 直接写进用户选定的目录根：root 合法、不会被改名、不阻塞。
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri)) ?: return false
        // 同名已存在就直接失败，让上层换个名字（uniqueTarget 已经负责加序号）
        runCatching { root.findFile(final.fileName) }.getOrNull()?.let {
            android.util.Log.i("QishuiDiag", "saf SKIP exists name=${final.fileName}")
            return false
        }
        val created = root.createFile("audio/*", final.fileName) ?: return false
        return try {
            contentResolver.openOutputStream(created.uri, "wt")?.use { output ->
                partFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw FileNotFoundException("无法写入所选目录")
            // createFile 已经把真实 URI 给到手了，之前白白丢掉，导致后面只能回头去 SAF 里
            // 查这个文件 —— 而那次查询必抛 UnsupportedOperationException（见 findDocumentUri）。
            createdDocumentCache[createdKey(treeUri, final.fileName)] = created.uri.toString()
            android.util.Log.i(
                "QishuiDiag",
                "saf WROTE root=$treeUri name=${final.fileName} bytes=${partFile.length()}",
            )
            partFile.delete()
            true
        } catch (error: Exception) {
            android.util.Log.e("QishuiDiag", "saf WRITE FAILED root=$treeUri", error)
            created.delete()
            false
        }
    }

    private fun createdKey(treeUri: String, fileName: String) = "$treeUri|$fileName"

    /**
     * 本进程创建出来的 SAF 文件 URI，按 `treeUri|fileName` 索引。
     *
     * `createFile()` 返回值里就有真实 URI，创建时记下来，
     * 后面 exists / publishedLocation 直接用，不必再回 SAF 里查 ——
     * 对 `SingleDocumentFile` 调 `findFile()` 会抛异常，而且会阻塞几十秒。
     */
    private val createdDocumentCache = ConcurrentHashMap<String, String>()

    private fun findDocumentUri(treeUri: String, fileName: String): String? {
        // 本进程刚创建的文件，URI 已经在手，直接用，不去 SAF 里查
        createdDocumentCache[createdKey(treeUri, fileName)]?.let { return it }
        val root = runCatching { DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri)) }.getOrNull()
            ?: return null
        // root 是 TreeDocument，对它 findFile 合法且不会阻塞
        return runCatching { root.findFile(fileName)?.uri?.toString() }.getOrNull()
    }

    private fun documentOf(documentUri: String): DocumentFile? =
        DocumentFile.fromSingleUri(applicationContext, Uri.parse(documentUri))
}


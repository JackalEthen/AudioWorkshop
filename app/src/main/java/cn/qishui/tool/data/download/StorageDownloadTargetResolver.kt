package cn.qishui.tool.data.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cn.qishui.tool.domain.download.DownloadTarget
import cn.qishui.tool.domain.download.DownloadTargetResolver
import cn.qishui.tool.domain.download.TREE_DIRECTORY_NAME
import java.io.File
import java.io.FileNotFoundException

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
            is DownloadTarget.TreeDocument ->
                target.documentUri?.let { documentOf(it)?.delete() } ?: true
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
        is DownloadTarget.TreeDocument -> "${target.treeUri}|${TREE_DIRECTORY_NAME}/${target.fileName}"
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
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri)) ?: return false
        val directory = findOrCreateDirectory(root) ?: return false
        directory.findFile(final.fileName)?.let { return false }
        val created = directory.createFile("audio/*", final.fileName) ?: return false
        return try {
            contentResolver.openOutputStream(created.uri, "wt")?.use { output ->
                partFile.inputStream().use { input -> input.copyTo(output) }
            } ?: throw FileNotFoundException("无法写入所选目录")
            partFile.delete()
            true
        } catch (error: Exception) {
            created.delete()
            false
        }
    }

    private fun findDocumentUri(treeUri: String, fileName: String): String? {
        val root = runCatching { DocumentFile.fromTreeUri(applicationContext, Uri.parse(treeUri)) }.getOrNull()
            ?: return null
        val directory = root.findFile(TREE_DIRECTORY_NAME)?.takeIf(DocumentFile::isDirectory) ?: return null
        return directory.findFile(fileName)?.uri?.toString()
    }

    private fun findOrCreateDirectory(root: DocumentFile): DocumentFile? {
        root.findFile(TREE_DIRECTORY_NAME)
            ?.takeIf(DocumentFile::isDirectory)
            ?.let { return it }
        return root.createDirectory(TREE_DIRECTORY_NAME)
    }

    private fun documentOf(documentUri: String): DocumentFile? =
        DocumentFile.fromSingleUri(applicationContext, Uri.parse(documentUri))
}

package cn.music.audioworkshop.domain.download

import java.io.File

sealed interface DownloadTarget {
    val stableKey: String

    data class AppFile(val absolutePath: String) : DownloadTarget {
        override val stableKey: String get() = absolutePath
    }

    data class TreeDocument(
        val treeUri: String,
        val fileName: String,
        val documentUri: String? = null,
    ) : DownloadTarget {
        override val stableKey: String
            get() = documentUri?.let { "$DOCUMENT_PREFIX$it" } ?: "$TREE_PREFIX$treeUri|$fileName"
    }
}

const val TREE_DIRECTORY_NAME = "音频工坊"

private const val TREE_PREFIX = "tree:"
private const val DOCUMENT_PREFIX = "doc:"

fun downloadTargetOf(stableKey: String): DownloadTarget = when {
    stableKey.startsWith(DOCUMENT_PREFIX) -> run {
        val documentUri = stableKey.removePrefix(DOCUMENT_PREFIX)
        val lastSegment = documentUri.substringAfterLast('/')
        DownloadTarget.TreeDocument(
            treeUri = "",
            fileName = lastSegment.substringAfterLast("%2F", lastSegment),
            documentUri = documentUri,
        )
    }

    stableKey.startsWith(TREE_PREFIX) -> stableKey.removePrefix(TREE_PREFIX).split('|', limit = 2)
        .let { parts ->
            DownloadTarget.TreeDocument(
                treeUri = parts.getOrElse(0) { "" },
                fileName = parts.getOrElse(1) { "" },
            )
        }

    else -> DownloadTarget.AppFile(stableKey)
}

interface DownloadTargetResolver {
    fun partTarget(taskId: String): DownloadTarget
    fun finalTarget(fileName: String): DownloadTarget
    fun lengthOf(target: DownloadTarget): Long
    fun exists(target: DownloadTarget): Boolean
    fun delete(target: DownloadTarget): Boolean
    fun publish(part: DownloadTarget, final: DownloadTarget): Boolean
    fun describe(target: DownloadTarget): String

    /** 编辑链路只认本地路径：SAF 目录里的文件复制一份到应用私有目录，AppFile 直接返回自身 */
    fun editableCopy(target: DownloadTarget): File?

    /**
     * 发布成功后拿回可长期引用的真实位置，给历史记录用。
     *
     * [publish] 只返回 Boolean，把建出来的文档 URI 丢了。记录里如果只存文件名，
     * 历史里的「打开 / 删除」就都定位不到文件 —— `Uri.parse("歌名.mp3")` 没有 scheme。
     *
     * SAF 目录返回文档的 `content://` URI，应用私有目录返回绝对路径，
     * 两种形态都能直接喂给 `Uri.parse`。
     */
    fun publishedLocation(target: DownloadTarget): String
}


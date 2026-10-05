package cn.music.audioworkshop.data.download

import java.io.File

internal fun managedFilesToDelete(
    deleteFile: Boolean,
    partPath: String?,
    finalPath: String?,
): List<String> = if (deleteFile) listOfNotNull(partPath, finalPath).distinct() else emptyList()

internal fun downloadFileSize(file: File): Long = if (file.isFile) file.length() else 0L

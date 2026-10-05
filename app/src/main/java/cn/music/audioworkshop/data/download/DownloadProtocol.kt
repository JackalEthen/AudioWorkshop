package cn.music.audioworkshop.data.download

data class ContentRange(
    val start: Long,
    val end: Long,
    val totalLength: Long?,
)

fun canAppendPartialResponse(
    responseCode: Int,
    localLength: Long,
    contentRangeValue: String?,
): Boolean = responseCode == 206 && parseContentRange(contentRangeValue)?.start == localLength

fun parseContentRange(value: String?): ContentRange? {
    val match = ContentRangePattern.matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start) return null
    val total = when (match.groupValues[3]) {
        "*" -> null
        else -> match.groupValues[3].toLongOrNull()?.takeIf { it > end }
    }
    if (match.groupValues[3] != "*" && total == null) return null
    return ContentRange(start, end, total)
}

fun parseUnsatisfiedContentLength(value: String?): Long? {
    val match = UnsatisfiedRangePattern.matchEntire(value?.trim().orEmpty()) ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it >= 0L }
}

fun isAcceptedContentType(value: String?): Boolean {
    val mediaType = value?.substringBefore(';')?.trim()?.lowercase() ?: return false
    return mediaType.startsWith("audio/") || mediaType == "video/mp4" || mediaType == "application/octet-stream"
}

fun safeDownloadFileName(title: String?, artist: String?, format: String): String {
    val base = "${cleanFilePart(title, "下载")}-${cleanFilePart(artist, "未知歌手")}"
    val extension = cleanFilePart(format, "audio").trimStart('.').ifBlank { "audio" }
    return "$base.$extension"
}

fun md5HexMatches(expected: String?, actual: String): Boolean {
    val normalized = expected?.trim()?.lowercase() ?: return false
    return Md5Pattern.matches(normalized) && Md5Pattern.matches(actual.lowercase()) && normalized == actual.lowercase()
}

private fun cleanFilePart(value: String?, fallback: String): String = value
    .orEmpty()
    .replace(InvalidFileNameCharacters, "_")
    .trim()
    .trim('.', ' ')
    .take(80)
    .ifBlank { fallback }

private val ContentRangePattern = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
private val UnsatisfiedRangePattern = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
private val InvalidFileNameCharacters = Regex("""[<>:"/\\|?*\u0000-\u001F\u007F]""")
private val Md5Pattern = Regex("""[0-9a-f]{32}""")

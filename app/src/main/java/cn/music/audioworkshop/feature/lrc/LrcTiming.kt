package cn.music.audioworkshop.feature.lrc

/**
 * 歌词行的时间换算。
 *
 * 单独抽出来是因为这些是最容易写错一位的地方：LRC 的小数部分是
 * 「百分之一秒」而不是「毫秒」，两位一位各不相同，
 * 而且超过 60 秒要进位、超过 60 分钟要再进位。
 */
object LrcTiming {

    /** [timeUs] -> `[mm:ss.SSS]`（三位毫秒，避免两位精度下打点误差被放大）。 */
    fun stamp(timeUs: Long): String {
        val totalMs = (timeUs / 1000L).coerceAtLeast(0L)
        return String.format(
            java.util.Locale.US,
            "%02d:%02d.%03d",
            totalMs / 60_000L,
            (totalMs / 1000L) % 60L,
            totalMs % 1000L,
        )
    }

    /** 用于界面显示：`1:05.32` 这种，一眼能看出秒和小数。 */
    fun display(timeUs: Long): String {
        val totalMs = (timeUs / 1000L).coerceAtLeast(0L)
        val minutes = totalMs / 60_000L
        val seconds = (totalMs / 1000L) % 60L
        val tenths = (totalMs % 1000L) / 100L
        return String.format(java.util.Locale.US, "%d:%02d.%d", minutes, seconds, tenths)
    }

    /**
     * 校验一段 LRC 源码，返回第一个问题的描述。
     *
     * 不用正则全量校验而是交给 [cn.music.audioworkshop.data.media.LrcCodec]：它才是真正解析的那份代码，
     * 两边规则不一致的话「校验通过但显示不出来」会更让人困惑。
     */
    /**
 * 保存前的提示信息。返回 null 表示一切正常。
 *
 * 现在不再「拦下」任何输入：带时间戳的、纯文本的、混着来的都能存。
 * 这里只负责告诉用户「有多少行没有时间戳、已经自动分配了」，
 * 否则静默按 4 秒排一遍，用户会以为时间打准了。
 */
fun describeTimestamps(raw: String): String? {
    if (raw.isBlank()) return null
    if (!hasNoTimestamp(raw)) return null
    val count = assignPlainTextTimings(raw).size
    if (count == 0) return "没有识别到歌词内容，只有元信息行"
    return "这 $count 句没有时间戳，已按每句 ${PLAIN_TEXT_INTERVAL_MS / 1000} 秒分配。" +
        "保存后可用每行的 −1s / +1s 校准到实际演唱位置"
}

/**
 * 纯文本歌词（没有时间戳）自动分配时间戳时，每句间隔多少毫秒。
 *
 * 4 秒是按中文歌一句的常见长度取的：太密会跟不上，太疏会显得敷衍。
 * 用户随后可以用每行的 `−1s` / `+1s` 精确到实际演唱位置。
 */
const val PLAIN_TEXT_INTERVAL_MS = 4_000L

/**
 * 判断一段文本是不是「完全没有时间戳的纯歌词」。
 *
 * 只要有任意一行能解析出时间戳，就算带时间戳 —— 混合粘贴时不要把
 * 已有时间戳的行也重新分配，那会毁掉用户已经对好的点。
 */
fun hasNoTimestamp(raw: String): Boolean = raw.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !isMetaLine(it) }
    .all { !TIMESTAMP.containsMatchIn(it) }

/** 纯文本里每一行一句，按固定间隔分配时间戳。 */
fun assignPlainTextTimings(raw: String, intervalMs: Long = PLAIN_TEXT_INTERVAL_MS): List<Pair<Long, String>> {
    var index = 0
    return raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !isMetaLine(it) }
        .map { text ->
            val startUs = index * intervalMs * 1000L
            index++
            startUs to text
        }
        .toList()
}

private val TIMESTAMP = Regex("""\[\d{1,3}:\d{1,2}[.:]?\d{0,3}]""")

/** `[ti:...]` `[ar:...]` 这类元信息行，不算歌词。 */
private fun isMetaLine(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return false
    return trimmed.substring(1).firstOrNull()?.isLetter() == true
}
}
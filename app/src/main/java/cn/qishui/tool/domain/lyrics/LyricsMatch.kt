package cn.qishui.tool.domain.lyrics

/**
 * 判断「按歌名在另一个平台搜到的歌」是不是同一首。
 *
 * 歌词跨平台反查的核心是**别配错**。宁可没有歌词，也不能在唱《信》的时候
 * 放《信乐团》的词 —— 那比空白更糟，用户分不清是歌错了还是词错了。
 *
 * 三道闸：歌名、歌手、时长。
 *
 * - **歌名**：归一化后相等，或一方包含另一方。
 *   包含是为了吃下 `信 (Live)` / `信（Live版）` 这类后缀差异。
 * - **歌手**：命中结果里的歌手名，要能在原歌手串里找到。
 *   只做「结果 → 原曲」这一个方向，且要求至少两个字：
 *   单字歌名很容易撞车（歌手「信」和歌名「信」），两个字以上基本不会。
 *   反方向不做 ——「信」在「信乐团」里成立，但那多半是另一首歌。
 * - **时长**：差不超过 [DURATION_TOLERANCE_MS]。Live 版、DJ 版时长差得多，
 *   这道闸能挡掉大部分。查得到时长就必须比，查不到就跳过这道闸。
 */
object LyricsMatch {

    /** 时长容差。实测同一首歌各平台误差在 1-2 秒内，8 秒足够容纳变奏版本。 */
    const val DURATION_TOLERANCE_MS = 8_000L

    private const val MIN_ARTIST_LEN = 2

    fun isSameSong(
        queryTitle: String,
        queryArtist: String?,
        queryDurationMs: Long?,
        hitTitle: String,
        hitArtist: String?,
        hitDurationMs: Long?,
    ): Boolean {
        if (!titleMatches(queryTitle, hitTitle)) return false
        if (!artistMatches(queryArtist, hitArtist)) return false
        return durationMatches(queryDurationMs, hitDurationMs)
    }

    /**
     * 歌名：归一化后相等，或短的一方被长的包住。
     *
     * 包含是为了吃下 `信 (Live)` / `离歌（Live版）` 这类后缀差异。中文歌名
     * 大量是单字（「信」「海」「光」），所以这里**不能**套最小长度门槛 ——
     * 那是歌手匹配才需要的，见 [artistMatches]。
     */
    fun titleMatches(queryTitle: String, hitTitle: String): Boolean {
        val q = normalize(queryTitle)
        val h = normalize(hitTitle)
        if (q.isEmpty() || h.isEmpty()) return false
        if (q == h) return true
        val shorter = if (q.length <= h.length) q else h
        val longer = if (q.length <= h.length) h else q
        return longer.contains(shorter)
    }

    /**
     * 歌手：结果里的歌手名要能在原曲歌手串里找到，且至少两个字。
     *
     * 只做「结果 → 原曲」这一个方向。���方向（拿原曲名去结果里找）在
     * 「信」这首歌撞上「信乐团」时会成立，但那是另一首歌。
     */
    fun artistMatches(queryArtist: String?, hitArtist: String?): Boolean {
        val q = normalize(queryArtist ?: "")
        val h = normalize(hitArtist ?: "")
        // 查不到歌手就不拦，交给歌名和时长判断。
        if (q.isEmpty() || h.isEmpty()) return true
        return h.length >= MIN_ARTIST_LEN && q.contains(h)
    }

    fun durationMatches(queryDurationMs: Long?, hitDurationMs: Long?): Boolean {
        if (queryDurationMs == null || queryDurationMs <= 0L) return true
        if (hitDurationMs == null || hitDurationMs <= 0L) return true
        return kotlin.math.abs(queryDurationMs - hitDurationMs) <= DURATION_TOLERANCE_MS
    }

    /** 归一化：转小写，去空白和一切装饰符号，只留下能辨认的部分。 */
    internal fun normalize(text: String): String = buildString(text.length) {
        for (ch in text.lowercase()) {
            if (ch.isWhitespace() || ch in PUNCTUATION) continue
            append(ch)
        }
    }

    private val PUNCTUATION = charArrayOf(
        '《', '》', '〈', '〉', '「', '」', '『', '』', '【', '】', '（', '）',
        '(', ')', '[', ']', '{', '}', '<', '>', '"', '\'', '‘', '’', '“', '”',
        '·', '・', '~', '-', '—', '–', '_', '.', ',', '!', '?', ':', ';', '@', '#',
        '/', '\\', '&', '|',
        '。', '，', '、', '！', '？', '：', '；', '．',
    )
}

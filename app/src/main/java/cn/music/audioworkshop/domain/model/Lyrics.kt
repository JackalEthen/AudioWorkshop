package cn.music.audioworkshop.domain.model

data class LyricWord(
    val text: String,
    val startUs: Long,
    val endUs: Long,
)

data class LyricLine(
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val words: List<LyricWord>,
)

data class LyricsTrack(
    val lines: List<LyricLine>,
    val offsetMs: Long,
) {
    companion object {
        val EMPTY = LyricsTrack(emptyList(), 0L)
    }
}

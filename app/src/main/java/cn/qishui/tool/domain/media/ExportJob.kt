package cn.qishui.tool.domain.media

import cn.qishui.tool.domain.model.FadeCurve
import cn.qishui.tool.domain.model.JoinTransition

data class ExportSegment(
    val sourceStartUs: Long,
    val sourceEndUs: Long,
)

data class ExportSource(
    val id: String,
    val localPath: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val lyrics: String?,
    /** 逐行打点后的歌词覆盖层，优先于 lyrics。 */
    val lyricsOverride: String? = null,
    val segments: List<ExportSegment>,
)

data class ExportJob(
    val jobId: String,
    val editProjectId: String,
    val outputTempPath: String,
    val sources: List<ExportSource>,
    val gainDb: Float,
    val format: ExportFormat = ExportFormat.MP3,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val fadeCurve: FadeCurve = FadeCurve.LINEAR,
    val lyricOffsetMs: Long,
    val joinTransition: JoinTransition = JoinTransition.NORMAL,
    val transitionMs: Long = 0L,
    val normalizeSources: Boolean = false,
    val trailingSilenceMs: Long = 0L,
    /** 目标码率 kbps，0 = 让编码器自己选（MP3 走 VBR）。无损格式忽略这个值。 */
    val bitrateKbps: Int = 0,
    /** 目标采样率 Hz，0 = 跟随源。 */
    val sampleRateHz: Int = 0,
    /** 0 = 跟随源，1 = 单声道，2 = 立体声。 */
    val channelMode: Int = 0,
)

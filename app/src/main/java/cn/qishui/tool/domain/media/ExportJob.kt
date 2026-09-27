package cn.qishui.tool.domain.media

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
    val segments: List<ExportSegment>,
)

data class ExportJob(
    val jobId: String,
    val editProjectId: String,
    val outputTempPath: String,
    val sources: List<ExportSource>,
    val gainDb: Float,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val lyricOffsetMs: Long,
)

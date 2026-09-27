package cn.qishui.tool.domain.media

enum class ExportStage {
    PREPARING,
    ENCODING,
    TAGGING,
    VALIDATING,
}

data class ExportProgress(
    val jobId: String,
    val stage: ExportStage,
    val fraction: Float,
)

sealed interface ExportResult {
    val jobId: String

    data class Completed(
        override val jobId: String,
        val outputPath: String,
        val durationMs: Long,
        val sizeBytes: Long,
    ) : ExportResult

    data class Failed(
        override val jobId: String,
        val stage: ExportStage,
        val code: String,
        val reason: String,
        val retryable: Boolean = false,
    ) : ExportResult
}

package cn.qishui.tool.domain.model

enum class ExportValidationStatus {
    PENDING,
    PASSED,
    FAILED,
}

data class ExportPackage(
    val sourceEditProjectId: String,
    val outputPath: String,
    val format: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
    val validationStatus: ExportValidationStatus,
)

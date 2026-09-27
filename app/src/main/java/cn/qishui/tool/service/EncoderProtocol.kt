package cn.qishui.tool.service

import cn.qishui.tool.domain.media.ExportProgress
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportStage
import org.json.JSONException
import org.json.JSONObject

object EncoderProtocol {
    const val MSG_EXPORT = 1
    const val MSG_CANCEL = 2
    const val MSG_PROGRESS = 3
    const val MSG_RESULT = 4

    const val KEY_JOB_PATH = "job_path"
    const val KEY_JOB_ID = "job_id"
    const val KEY_PROGRESS = "progress"
    const val KEY_RESULT = "result"

    fun encodeProgress(progress: ExportProgress): ByteArray = JSONObject()
        .put("jobId", progress.jobId)
        .put("stage", progress.stage.name)
        .put("fraction", progress.fraction.toDouble())
        .toString()
        .toByteArray()

    fun decodeProgress(payload: ByteArray?): ExportProgress = parse(payload).let { root ->
        ExportProgress(
            jobId = root.optString("jobId"),
            stage = stageOf(root.optString("stage")),
            fraction = root.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f),
        )
    }

    fun encodeResult(result: ExportResult): ByteArray {
        val root = JSONObject().put("jobId", result.jobId)
        return when (result) {
            is ExportResult.Completed -> root
                .put("ok", true)
                .put("outputPath", result.outputPath)
                .put("durationMs", result.durationMs)
                .put("sizeBytes", result.sizeBytes)
            is ExportResult.Failed -> root
                .put("ok", false)
                .put("stage", result.stage.name)
                .put("code", result.code)
                .put("reason", result.reason)
                .put("retryable", result.retryable)
        }.toString().toByteArray()
    }

    fun decodeResult(payload: ByteArray?): ExportResult {
        val root = parse(payload)
        val jobId = root.optString("jobId")
        return if (root.optBoolean("ok")) {
            ExportResult.Completed(
                jobId = jobId,
                outputPath = root.optString("outputPath"),
                durationMs = root.optLong("durationMs"),
                sizeBytes = root.optLong("sizeBytes"),
            )
        } else {
            ExportResult.Failed(
                jobId = jobId,
                stage = stageOf(root.optString("stage")),
                code = root.optString("code", "FAILED"),
                reason = root.optString("reason", "编码进程返回未知错误"),
                retryable = root.optBoolean("retryable"),
            )
        }
    }

    private fun parse(payload: ByteArray?): JSONObject = try {
        JSONObject(String(payload ?: ByteArray(0)))
    } catch (error: JSONException) {
        JSONObject()
    }

    private fun stageOf(name: String?): ExportStage =
        ExportStage.entries.firstOrNull { it.name == name } ?: ExportStage.PREPARING
}

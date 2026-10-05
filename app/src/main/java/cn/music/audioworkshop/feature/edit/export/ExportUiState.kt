package cn.music.audioworkshop.feature.edit.export

import cn.music.audioworkshop.domain.media.ExportProgress
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportStage

data class ExportUiState(
    val jobId: String? = null,
    /** 正在写的文件名。目标目录来自设置页，不是用户现场选的。 */
    val fileName: String? = null,
    val stage: ExportStage? = null,
    val fraction: Float = 0f,
    val isRunning: Boolean = false,
    val isCopying: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
) {
    val isIdle: Boolean
        get() = !isRunning && !isCopying

    val statusLabel: String
        get() = when {
            isCopying -> "正在保存到下载目录"
            isRunning -> "${stage?.label().orEmpty()} ${(fraction * 100).toInt()}%"
            isSuccess -> "导出完成"
            error != null -> error
            else -> "开始导出"
        }
}

fun ExportStage.label(): String = when (this) {
    ExportStage.PREPARING -> "准备中"
    ExportStage.ENCODING -> "编码中"
    ExportStage.TAGGING -> "写入标签"
    ExportStage.VALIDATING -> "校验中"
}

sealed interface ExportEvent {
    data class Started(val jobId: String, val fileName: String) : ExportEvent
    data class Progressed(val progress: ExportProgress) : ExportEvent
    data class Completed(val result: ExportResult.Completed) : ExportEvent
    data class Failed(val message: String) : ExportEvent
    data class Succeeded(val bytes: Long) : ExportEvent
    data object Cancelled : ExportEvent
    data object Reset : ExportEvent
}

fun reduceExport(state: ExportUiState, event: ExportEvent): ExportUiState = when (event) {
    is ExportEvent.Started -> ExportUiState(
        jobId = event.jobId,
        fileName = event.fileName,
        stage = ExportStage.PREPARING,
        fraction = 0f,
        isRunning = true,
    )
    is ExportEvent.Progressed -> if (event.progress.jobId != state.jobId || !state.isRunning) {
        state
    } else {
        state.copy(stage = event.progress.stage, fraction = event.progress.fraction.coerceIn(0f, 1f))
    }
    is ExportEvent.Completed -> if (event.result.jobId != state.jobId) {
        state
    } else {
        state.copy(isRunning = false, isCopying = true, stage = ExportStage.VALIDATING, fraction = 1f)
    }
    is ExportEvent.Succeeded -> state.copy(
        isCopying = false,
        isRunning = false,
        isSuccess = true,
        stage = null,
        fraction = 1f,
        error = null,
    )
    is ExportEvent.Failed -> state.copy(
        isCopying = false,
        isRunning = false,
        isSuccess = false,
        error = event.message,
    )
    ExportEvent.Cancelled -> ExportUiState(error = "导出已取消")
    ExportEvent.Reset -> ExportUiState()
}

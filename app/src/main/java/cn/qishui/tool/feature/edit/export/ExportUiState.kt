package cn.qishui.tool.feature.edit.export

import cn.qishui.tool.domain.media.ExportProgress
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportStage

data class ExportUiState(
    val jobId: String? = null,
    val targetUri: String? = null,
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
            isCopying -> "正在写入所选位置"
            isRunning -> "${stage?.label().orEmpty()} ${(fraction * 100).toInt()}%"
            isSuccess -> "导出完成"
            error != null -> error
            else -> "导出 MP3"
        }
}

fun ExportStage.label(): String = when (this) {
    ExportStage.PREPARING -> "准备中"
    ExportStage.ENCODING -> "编码中"
    ExportStage.TAGGING -> "写入标签"
    ExportStage.VALIDATING -> "校验中"
}

sealed interface ExportEvent {
    data class Started(val jobId: String, val targetUri: String) : ExportEvent
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
        targetUri = event.targetUri,
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

package cn.qishui.tool.data.encoder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import cn.qishui.tool.domain.ExportPackageRepository
import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportJobCodec
import cn.qishui.tool.domain.media.ExportPaths
import cn.qishui.tool.domain.media.ExportProgress
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportStage
import cn.qishui.tool.domain.model.ExportPackage
import cn.qishui.tool.domain.model.ExportValidationStatus
import cn.qishui.tool.service.EncoderProtocol
import cn.qishui.tool.service.EncoderService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface EncoderState {
    data object Idle : EncoderState

    data class InProgress(val jobId: String, val progress: ExportProgress) : EncoderState

    data class Finished(val result: ExportResult) : EncoderState
}

class EncoderClient(
    private val context: Context,
    private val exportPackageRepository: ExportPackageRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<EncoderState>(EncoderState.Idle)
    val state: StateFlow<EncoderState> = mutableState

    private val replyHandler = Handler(
        Looper.getMainLooper(),
        Handler.Callback { message -> handleReply(message) },
    )
    private val replyMessenger = Messenger(replyHandler)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceMessenger = Messenger(binder)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            failActiveJob("编码进程已退出，可重试", retryable = true)
        }

        override fun onBindingDied(name: ComponentName?) {
            serviceMessenger = null
            failActiveJob("编码服务绑定失效，可重试", retryable = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            serviceMessenger = null
            failActiveJob("编码服务不可用，可重试", retryable = true)
        }
    }

    private var serviceMessenger: Messenger? = null
    private var bound = false
    private var activeJob: ExportJob? = null
    private var activeJobFile: File? = null
    private var resultListener: ((ExportResult) -> Unit)? = null

    fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, EncoderService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    fun unbind() {
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        serviceMessenger = null
    }

    fun export(job: ExportJob, onResult: (ExportResult) -> Unit) {
        val active = activeJob
        if (active != null) {
            onResult(
                ExportResult.Failed(
                    jobId = job.jobId,
                    stage = ExportStage.PREPARING,
                    code = CODE_DUPLICATE,
                    reason = "已有导出任务进行中: ${active.jobId}",
                ),
            )
            return
        }
        val messenger = serviceMessenger
        if (messenger == null) {
            onResult(
                ExportResult.Failed(
                    jobId = job.jobId,
                    stage = ExportStage.PREPARING,
                    code = CODE_NOT_BOUND,
                    reason = "编码服务未连接",
                    retryable = true,
                ),
            )
            return
        }
        activeJob = job
        resultListener = onResult
        mutableState.value = EncoderState.InProgress(
            jobId = job.jobId,
            progress = ExportProgress(jobId = job.jobId, stage = ExportStage.PREPARING, fraction = 0f),
        )
        // ponytail: 只跨进程传文件路径，任务 JSON 落盘，避免 Binder 事务超限
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { writeJobFile(job) } }
                .getOrElse {
                    failActiveJob("无法写入导出任务: ${it.message}", retryable = true)
                    return@launch
                }
            activeJobFile = file
            runCatching {
                messenger.send(
                    Message.obtain(null, EncoderProtocol.MSG_EXPORT).apply {
                        data = Bundle().apply {
                            putString(EncoderProtocol.KEY_JOB_ID, job.jobId)
                            putString(EncoderProtocol.KEY_JOB_PATH, file.absolutePath)
                        }
                        replyTo = replyMessenger
                    },
                )
            }.onFailure {
                failActiveJob("无法提交导出任务: ${it.message}", retryable = true)
            }
        }
    }

    fun cancel(jobId: String) {
        if (activeJob?.jobId != jobId) return
        serviceMessenger?.send(
            Message.obtain(null, EncoderProtocol.MSG_CANCEL).apply {
                data = Bundle().apply { putString(EncoderProtocol.KEY_JOB_ID, jobId) }
            },
        )
    }

    private fun handleReply(message: Message): Boolean {
        when (message.what) {
            EncoderProtocol.MSG_PROGRESS -> {
                val job = activeJob ?: return true
                val progress = EncoderProtocol.decodeProgress(
                    message.data.getString(EncoderProtocol.KEY_PROGRESS)?.toByteArray(),
                )
                if (progress.jobId == job.jobId) {
                    mutableState.value = EncoderState.InProgress(job.jobId, progress)
                }
            }
            EncoderProtocol.MSG_RESULT -> finish(
                EncoderProtocol.decodeResult(message.data.getString(EncoderProtocol.KEY_RESULT)?.toByteArray()),
            )
        }
        return true
    }

    private fun finish(result: ExportResult) {
        val job = activeJob ?: return
        val listener = resultListener
        activeJob = null
        resultListener = null
        activeJobFile?.delete()
        activeJobFile = null
        val outcome = if (result is ExportResult.Completed) {
            record(job, result)
            result
        } else {
            ExportPaths.partOf(job.outputTempPath).delete()
            result
        }
        mutableState.value = EncoderState.Finished(outcome)
        runCatching { listener?.invoke(outcome) }
    }

    private fun failActiveJob(reason: String, retryable: Boolean) {
        val job = activeJob ?: return
        finish(
            ExportResult.Failed(
                jobId = job.jobId,
                stage = ExportStage.PREPARING,
                code = CODE_DISCONNECTED,
                reason = reason,
                retryable = retryable,
            ),
        )
    }

    private fun record(job: ExportJob, result: ExportResult.Completed) {
        scope.launch {
            runCatching {
                exportPackageRepository.upsert(
                    ExportPackage(
                        sourceEditProjectId = job.editProjectId,
                        outputPath = result.outputPath,
                        format = MP3_FORMAT,
                        durationMs = result.durationMs,
                        sizeBytes = result.sizeBytes,
                        createdAt = System.currentTimeMillis(),
                        validationStatus = ExportValidationStatus.PASSED,
                    ),
                )
            }
        }
    }

    private fun writeJobFile(job: ExportJob): File {
        val directory = File(context.cacheDir, "export-jobs")
        if (!directory.exists()) directory.mkdirs()
        val safeName = job.jobId.replace(UNSAFE_NAME_CHARS, "_")
        return File(directory, "$safeName.json").apply { writeText(ExportJobCodec.encode(job)) }
    }

    private companion object {
        const val MP3_FORMAT = "mp3"
        const val CODE_DUPLICATE = "DUPLICATE"
        const val CODE_NOT_BOUND = "NOT_BOUND"
        const val CODE_DISCONNECTED = "DISCONNECTED"
        val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}

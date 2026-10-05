package cn.music.audioworkshop.data.encoder

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
import cn.music.audioworkshop.domain.media.ExportJob
import cn.music.audioworkshop.domain.media.ExportJobCodec
import cn.music.audioworkshop.domain.media.ExportPaths
import cn.music.audioworkshop.domain.media.ExportProgress
import cn.music.audioworkshop.domain.media.ExportResult
import cn.music.audioworkshop.domain.media.ExportStage
import cn.music.audioworkshop.service.EncoderProtocol
import cn.music.audioworkshop.service.EncoderService
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
            android.util.Log.i("QishuiEncoder", "bind OK $name")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            android.util.Log.i("QishuiEncoder", "bind DISCONNECTED $name")
            failActiveJob("编码进程已退出，可重试", retryable = true)
        }

        override fun onBindingDied(name: ComponentName?) {
            serviceMessenger = null
            android.util.Log.i("QishuiEncoder", "bind DIED $name")
            failActiveJob("编码服务绑定失效，可重试", retryable = true)
        }

        override fun onNullBinding(name: ComponentName?) {
            serviceMessenger = null
            android.util.Log.i("QishuiEncoder", "bind NULL $name")
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
            android.util.Log.e("QishuiEncoder", "export 拒绝：serviceMessenger=null bound=$bound")
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
        // ponytail: 只跨进程传文件路径，任务 JSON落盘，避免 Binder 事务超限
        scope.launch {
            // 编码器直接往 outputTempPath 的 .part 写，父目录不存在就是 ENOENT。
            // 各功能页的 exportTempDirectory 只是路径声明，不会建目录，所以在这里统一建。
            val prepared = withContext(Dispatchers.IO) {
                runCatching {
                    File(job.outputTempPath).parentFile?.takeIf { !it.exists() }?.mkdirs()
                    writeJobFile(job)
                }
            }.getOrElse {
                failActiveJob("无法写入导出任务: ${it.message}", retryable = true)
                return@launch
            }
            activeJobFile = prepared
            runCatching {
                messenger.send(
                    Message.obtain(null, EncoderProtocol.MSG_EXPORT).apply {
                        data = Bundle().apply {
                            putString(EncoderProtocol.KEY_JOB_ID, job.jobId)
                            putString(EncoderProtocol.KEY_JOB_PATH, prepared.absolutePath)
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
        val job = activeJob ?: run {
            android.util.Log.e("QishuiEncoder", "finish 时 activeJob 已空，丢弃结果 $result")
            return
        }
        android.util.Log.i("QishuiEncoder", "导出结束 job=${job.jobId} $result")
        val listener = resultListener
        activeJob = null
        resultListener = null
        activeJobFile?.delete()
        activeJobFile = null
        // 编码成功不落库：此刻 outputPath 指向的 cache 临时文件马上会被
        // ExportPublisher 删掉，记它等于记一个永远打不开的路径。
        // 历史记录由 ExportPublisher 在发布成功后用真实落点写。
        if (result !is ExportResult.Completed) {
            ExportPaths.partOf(job.outputTempPath).delete()
        }
        mutableState.value = EncoderState.Finished(result)
        runCatching { listener?.invoke(result) }
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

    private fun writeJobFile(job: ExportJob): File {
        val directory = File(context.cacheDir, "export-jobs")
        if (!directory.exists()) directory.mkdirs()
        val safeName = job.jobId.replace(UNSAFE_NAME_CHARS, "_")
        return File(directory, "$safeName.json").apply { writeText(ExportJobCodec.encode(job)) }
    }

    private companion object {
        const val CODE_DUPLICATE = "DUPLICATE"
        const val CODE_NOT_BOUND = "NOT_BOUND"
        const val CODE_DISCONNECTED = "DISCONNECTED"
        val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}

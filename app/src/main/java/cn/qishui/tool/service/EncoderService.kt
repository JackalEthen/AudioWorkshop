package cn.qishui.tool.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import cn.qishui.tool.app.ProcessIdentity
import cn.qishui.tool.data.media.AudioFileProbe
import cn.qishui.tool.data.media.QsmusicLyricParser
import cn.qishui.tool.domain.media.ExportJob
import cn.qishui.tool.domain.media.ExportJobCodec
import cn.qishui.tool.domain.media.ExportResult
import cn.qishui.tool.domain.media.ExportStage
import cn.qishui.tool.media.export.ExportEngine
import cn.qishui.tool.media.pcm.PcmChunkReader
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

class EncoderService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val cancelledJobs: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val clientHandler = Handler(
        Looper.getMainLooper(),
        Handler.Callback { message -> handleClientMessage(message) },
    )
    private val serviceMessenger = Messenger(clientHandler)
    private lateinit var engine: ExportEngine

    override fun onCreate() {
        super.onCreate()
        check(!ProcessIdentity.isMainProcess(this)) { "EncoderService 只能在 :encoder 进程运行" }
        engine = ExportEngine(
            probe = AudioFileProbe(),
            reader = PcmChunkReader(applicationContext),
            lyricParser = QsmusicLyricParser(),
        )
    }

    override fun onBind(intent: Intent?): IBinder = serviceMessenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun handleClientMessage(message: Message): Boolean {
        when (message.what) {
            EncoderProtocol.MSG_EXPORT -> startExport(message)
            EncoderProtocol.MSG_CANCEL -> {
                message.data.getString(EncoderProtocol.KEY_JOB_ID)?.let { cancelledJobs.add(it) }
            }
        }
        return true
    }

    private fun startExport(message: Message) {
        val replyTo = message.replyTo ?: return
        val requestedJobId = message.data.getString(EncoderProtocol.KEY_JOB_ID).orEmpty()
        val job = readJob(message.data.getString(EncoderProtocol.KEY_JOB_PATH))
        if (job == null) {
            reply(replyTo, EncoderProtocol.MSG_RESULT, EncoderProtocol.encodeResult(invalidJob(requestedJobId)))
            return
        }
        cancelledJobs.remove(job.jobId)
        worker.execute {
            val result = engine.execute(
                job = job,
                shouldCancel = { cancelledJobs.contains(job.jobId) },
                onProgress = { progress ->
                    reply(replyTo, EncoderProtocol.MSG_PROGRESS, EncoderProtocol.encodeProgress(progress))
                },
            )
            cancelledJobs.remove(job.jobId)
            reply(replyTo, EncoderProtocol.MSG_RESULT, EncoderProtocol.encodeResult(result))
        }
    }

    // ponytail: 只接受本应用任务目录内的文件，避免任意路径读取
    private fun readJob(path: String?): ExportJob? {
        val root = runCatching { File(applicationContext.cacheDir, "export-jobs").canonicalFile }.getOrNull()
            ?: return null
        val file = path?.let { runCatching { File(it).canonicalFile }.getOrNull() } ?: return null
        if (file.parentFile != root || !file.isFile) return null
        val payload = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { ExportJobCodec.decode(payload) }.getOrNull()
    }

    private fun reply(replyTo: Messenger, what: Int, payload: ByteArray) {
        val key = if (what == EncoderProtocol.MSG_PROGRESS) {
            EncoderProtocol.KEY_PROGRESS
        } else {
            EncoderProtocol.KEY_RESULT
        }
        replyTo.send(
            Message.obtain(null, what).apply {
                data = Bundle().apply { putString(key, String(payload)) }
            },
        )
    }

    private fun invalidJob(jobId: String): ExportResult.Failed = ExportResult.Failed(
        jobId = jobId,
        stage = ExportStage.PREPARING,
        code = "INVALID_JOB",
        reason = "任务参数无效",
    )
}

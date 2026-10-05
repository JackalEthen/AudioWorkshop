package cn.music.audioworkshop.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音源链路的文件日志。
 *
 * 为什么不用 logcat：音源问题排查全靠日志，而 logcat 缓冲会被系统通知、
 * 厂商组件刷爆，实测 16MB 也留不住关键那几行，还要靠 timing 碰运气。
 * 音源一次复现很慢（日志滚没了就得让用户再点一次），所以写文件。
 *
 * 位置：`filesDir/lxsource.log`。一行一条，带时间戳。
 * 只在 debug 包保留，release 不写盘。
 */
object SourceLog {

    private const val FILE_NAME = "lxsource.log"
    private const val MAX_BYTES = 512 * 1024

    private val lock = Any()
    private var file: File? = null
    private var formatter: SimpleDateFormat? = null

    fun init(context: Context) {
        if (file != null) return
        synchronized(lock) {
            if (file != null) return
            val dir = context.applicationContext.filesDir
            if (!dir.exists()) dir.mkdirs()
            file = File(dir, FILE_NAME)
            formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        }
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        val target = file ?: return
        val stamp = formatter?.format(Date()) ?: return
        synchronized(lock) {
            try {
                // 超限就截半截，避免无限增长
                if (target.exists() && target.length() > MAX_BYTES) {
                    val kept = target.readLines().takeLast(MAX_BYTES.toInt() / 60)
                    target.writeText(kept.joinToString("\n"))
                }
                target.appendText("$stamp [$tag] $message\n")
            } catch (error: Throwable) {
                // 写日志失败绝不能影响播放
            }
        }
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        i(tag, buildString {
            append(message)
            if (error != null) {
                append(" | ").append(error.javaClass.simpleName)
                error.message?.let { append(": ").append(it) }
            }
        })
    }

    /** 供 adb pull 之后阅读。 */
    fun path(context: Context): String = File(context.applicationContext.filesDir, FILE_NAME).absolutePath
}

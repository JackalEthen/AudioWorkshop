package cn.qishui.tool.feature.edit.export

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExportTargetWriter(context: Context) {

    private val applicationContext = context.applicationContext

    suspend fun copy(tempPath: String, target: Uri): Long = withContext(Dispatchers.IO) {
        val source = File(tempPath)
        if (!source.isFile) throw IOException("导出临时文件不存在: $tempPath")
        val written = try {
            val stream = applicationContext.contentResolver.openOutputStream(target, "wt")
                ?: throw IOException("无法写入所选位置: $target")
            stream.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
        } catch (failure: IOException) {
            discard(target)
            throw failure
        }
        if (written != source.length()) {
            discard(target)
            throw IOException("写入不完整: 已写 $written 字节, 预期 ${source.length()}")
        }
        source.delete()
        written
    }

    private fun discard(target: Uri) {
        runCatching { applicationContext.contentResolver.delete(target, null, null) }
    }
}

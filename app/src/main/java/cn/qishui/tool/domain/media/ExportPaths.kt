package cn.qishui.tool.domain.media

import java.io.File

object ExportPaths {
    const val PART_SUFFIX = ".part"
    const val TAG_SUFFIX = ".tag"

    fun partOf(outputTempPath: String): File = File(outputTempPath + PART_SUFFIX)

    fun tagOf(outputTempPath: String): File = File(outputTempPath + TAG_SUFFIX)

    fun partPathOf(outputTempPath: String): String = partOf(outputTempPath).path

    fun tagPathOf(outputTempPath: String): String = tagOf(outputTempPath).path

    /** 格式化音乐时重采样出来的中间 WAV。 */
    fun normalizedOf(outputTempPath: String, index: Int): File =
        File(outputTempPath + ".norm$index.wav")
}

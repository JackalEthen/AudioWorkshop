package cn.music.audioworkshop.media.export

import java.io.File
import java.io.RandomAccessFile

/**
 * FLAC STREAMINFO 的 totalSamples 回填。
 *
 * **为什么必须补这一步。** `c2.android.flac.encoder` 是流式编码器，结束时不会
 * 回填文件头里的总样本数 —— 实测导出后 STREAMINFO 的 totalSamples = 0，
 * 于是播放器算不出总时长，表现为「只能一秒一秒往前跳」。
 *
 * 编码器做不到是因为它只顺序写；我们是导出完成后再打开文件改固定偏移，能补上。
 *
 * 布局（FLAC 规范）：
 * - 0..3   "fLaC"
 * - 4      元数据块头（type 低 7 位 = 0 即 STREAMINFO，最后一块标记在最高位）
 * - 5..7   块长度（24 位大端，不含这 4 字节头）
 * - 8..41  STREAMINFO 正文（34 字节）
 *
 * 正文里 10..17 是那个大 packed 字段：
 * - 0..19  minBlockSize，20..39 maxBlockSize
 * - 40..63 minFrameSize，64..87 maxFrameSize
 * - 88..111 sampleRate（20 位）
 * - 112..114 channels-1（3 位）
 * - 115..119 bitsPerSample-1（5 位）
 * - 120..143 totalSamples（36 位）  ← 要改的就是这个
 *
 * 所以绝对偏移是 8 + 16 = 24，长度 8 字节（36 位用 8 字节存，高位必为 0）。
 */
object FlacHeader {

    /** totalSamples 字段的绝对偏移与长度。 */
    private const val TOTAL_SAMPLES_OFFSET = 24
    private const val TOTAL_SAMPLES_BYTES = 8
    private const val FLAC_MAGIC = "fLaC"

    /**
     * 把 [totalSamples] 写进 [file] 的 STREAMINFO。
     *
     * @return true 表示已写入；false 表示文件结构不符合预期（保持原样，不猜）
     */
    fun writeTotalSamples(file: File, totalSamples: Long): Boolean {
        if (totalSamples <= 0L || !file.isFile || file.length() < TOTAL_SAMPLES_OFFSET + TOTAL_SAMPLES_BYTES) {
            return false
        }
        // 36 位上限，超过说明调用方算错了，写进去会破坏文件
        if (totalSamples > MAX_TOTAL_SAMPLES) return false
        return runCatching {
            RandomAccessFile(file, "rw").use { raf ->
                // 36 位值左对齐进 8 字节大端字段：先清空，再逐字节写入
                val bytes = ByteArray(TOTAL_SAMPLES_BYTES)
                var value = totalSamples
                for (index in bytes.indices.reversed()) {
                    bytes[index] = (value and 0xFFL).toByte()
                    value = value ushr 8
                }
                raf.seek(TOTAL_SAMPLES_OFFSET.toLong())
                raf.write(bytes)
            }
            true
        }.getOrDefault(false)
    }

    /** 读回 STREAMINFO 的 totalSamples，测试与自查用。 */
    fun readTotalSamples(file: File): Long? {
        if (!file.isFile || file.length() < TOTAL_SAMPLES_OFFSET + TOTAL_SAMPLES_BYTES) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(TOTAL_SAMPLES_OFFSET.toLong())
                val bytes = ByteArray(TOTAL_SAMPLES_BYTES)
                raf.readFully(bytes)
                var value = 0L
                for (index in bytes.indices) {
                    value = (value shl 8) or (bytes[index].toLong() and 0xFFL)
                }
                value
            }
        }.getOrNull()
    }

    /** 确认文件确实是 FLAC，避免往别的文件上乱写。 */
    fun looksLikeFlac(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) return false
            String(magic, Charsets.US_ASCII) == FLAC_MAGIC
        }
    }.getOrDefault(false)

    private const val MAX_TOTAL_SAMPLES: Long = 0xFFFFFFFFFL
}

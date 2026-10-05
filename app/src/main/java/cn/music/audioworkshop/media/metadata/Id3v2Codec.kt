package cn.music.audioworkshop.media.metadata

import cn.music.audioworkshop.domain.model.LyricWord
import cn.music.audioworkshop.domain.model.LyricsTrack
import cn.music.audioworkshop.domain.model.TrackMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_16BE
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.charset.StandardCharsets.UTF_8

class Id3Exception(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SyncedText(
    val text: String,
    val timestampMs: Int,
)

data class Id3v2Tags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val date: String?,
    val artworkBytes: ByteArray?,
    val artworkMimeType: String?,
    val unsyncedLyrics: String?,
    val syncedLyrics: List<SyncedText>,
    val comment: String? = null,
)

object Id3v2Codec {

    private const val LANGUAGE = "zho"
    private const val ENCODING_UTF16 = 0x01
    private const val ENCODING_UTF16BE = 0x02
    private const val SYLT_TIMESTAMP_MS = 0x02
    private const val SYLT_CONTENT_LYRICS = 0x01
    private const val PICTURE_COVER_FRONT = 0x03
    private const val MAX_TIMESTAMP_MS = Int.MAX_VALUE.toLong()
    private val BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val NULL_UTF16 = byteArrayOf(0, 0)
    private val EMPTY_TAGS = Id3v2Tags(null, null, null, null, null, null, null, emptyList())

    fun write(audioFile: File, outputFile: File, metadata: TrackMetadata, lyricsTrack: LyricsTrack?) {
        val frames = mutableListOf<ByteArray>()
        addTextFrame(frames, "TIT2", metadata.title)
        addTextFrame(frames, "TPE1", metadata.artist)
        addTextFrame(frames, "TALB", metadata.album)
        addTextFrame(frames, "TDRC", metadata.year)
        addTextFrame(frames, "COMM", metadata.comment)
        val artwork = metadata.artworkBytes
        if (artwork != null && artwork.isNotEmpty()) {
            frames += apicFrame(metadata.artworkMimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg", artwork)
        }
        val plainLyrics = lyricsTrack?.lines.orEmpty().joinToString("\n") { it.text }
        if (plainLyrics.isNotBlank()) frames += unsyncedLyricsFrame(plainLyrics)
        val syncedLyrics = syncedEntries(lyricsTrack)
        if (syncedLyrics.isNotEmpty()) frames += syncedLyricsFrame(syncedLyrics)
        // 歌词没写进去时用户只看到「导出成功但播放器里没词」，完全查不出是哪一步丢的
        android.util.Log.i(
            "QishuiLrc",
            "Id3v2Codec.write lines=${lyricsTrack?.lines?.size ?: -1} " +
                "plain=${plainLyrics.length}chars synced=${syncedLyrics.size} " +
                "title=${metadata.title} frames=${frames.size}",
        )
        val body = frames.fold(ByteArray(0)) { acc, frame -> acc + frame }
        outputFile.outputStream().buffered().use { output ->
            output.write(tagHeader(body.size))
            output.write(body)
            audioFile.inputStream().buffered().use { it.copyTo(output) }
        }
    }

    fun read(inputFile: File): Id3v2Tags {
        if (!inputFile.isFile) throw Id3Exception("文件不存在: ${inputFile.path}")
        try {
            inputFile.inputStream().buffered().use { stream ->
                val header = stream.readFully(10) ?: return EMPTY_TAGS
                if (String(header, 0, 3, ISO_8859_1) != "ID3") return EMPTY_TAGS
                val major = header[3].toInt() and 0xFF
                if (major < 2 || major > 4) return EMPTY_TAGS
                val tagSize = synchsafeToInt(bigEndianLong(header, 6, 4))
                val reader = FrameReader(stream, major, tagSize)
                val tags = TagAccumulator()
                while (true) {
                    val frame = reader.next() ?: break
                    if (isUnsupported(major, frame.flags)) continue
                    val data = if (isUnsynchronised(major, frame.flags)) deunsynchronise(frame.data) else frame.data
                    tags.accept(major, frame.id, data)
                }
                return tags.build()
            }
        } catch (error: Id3Exception) {
            throw error
        } catch (error: Exception) {
            throw Id3Exception("读取 ID3 标签失败: ${inputFile.path}", error)
        }
    }

    fun syncedEntries(lyricsTrack: LyricsTrack?): List<SyncedText> {
        if (lyricsTrack == null) return emptyList()
        var previous = 0
        return lyricsTrack.lines
            // 逐字和整行混着用的歌很常见，没有字级时间戳的行整行算一个单元，
            // 直接 flatMap { words } 会把这行从时间轴上抹掉，播放器高亮会跳过去。
            .flatMap { line ->
                line.words.ifEmpty { listOf(LyricWord(line.text, line.startUs, line.endUs)) }
            }
            .map { word ->
                val timestampMs = (word.startUs / 1000L).coerceIn(previous.toLong(), MAX_TIMESTAMP_MS).toInt()
                previous = timestampMs
                SyncedText(word.text, timestampMs)
            }
    }

    private fun addTextFrame(frames: MutableList<ByteArray>, id: String, value: String?) {
        val text = value?.takeIf { it.isNotBlank() } ?: return
        if (id == "COMM") {
            // COMM 帧正文：编码 + 语言 + 短描述(空) + 正文
            frames += frame(
                id,
                byteArrayOf(ENCODING_UTF16.toByte()) +
                    LANGUAGE.toByteArray(ISO_8859_1) +
                    BOM +
                    text.toByteArray(UTF_16LE),
            )
            return
        }
        frames += frame(id, byteArrayOf(ENCODING_UTF16.toByte()) + BOM + text.toByteArray(UTF_16LE))
    }

    private fun unsyncedLyricsFrame(text: String): ByteArray = frame(
        "USLT",
        byteArrayOf(ENCODING_UTF16.toByte()) + LANGUAGE.toByteArray(ISO_8859_1) + BOM + NULL_UTF16 + BOM + text.toByteArray(UTF_16LE),
    )

    private fun syncedLyricsFrame(entries: List<SyncedText>): ByteArray {
        var body = byteArrayOf(ENCODING_UTF16.toByte()) +
            LANGUAGE.toByteArray(ISO_8859_1) +
            byteArrayOf(SYLT_TIMESTAMP_MS.toByte(), SYLT_CONTENT_LYRICS.toByte()) +
            BOM +
            NULL_UTF16
        for (entry in entries) {
            body += entry.text.toByteArray(UTF_16LE) + NULL_UTF16 + bigEndianBytes(entry.timestampMs)
        }
        return frame("SYLT", body)
    }

    private fun apicFrame(mimeType: String, artwork: ByteArray): ByteArray = frame(
        "APIC",
        byteArrayOf(ENCODING_UTF16.toByte()) +
            mimeType.toByteArray(ISO_8859_1) +
            byteArrayOf(0, PICTURE_COVER_FRONT.toByte()) +
            BOM +
            NULL_UTF16 +
            artwork,
    )

    private fun frame(id: String, body: ByteArray): ByteArray =
        id.toByteArray(ISO_8859_1) + synchsafeBytes(body.size) + byteArrayOf(0, 0) + body

    private fun tagHeader(bodySize: Int): ByteArray = "ID3".toByteArray(ISO_8859_1) +
        byteArrayOf(0x04, 0x00, 0x00) +
        synchsafeBytes(bodySize)

    private fun synchsafeBytes(value: Int): ByteArray {
        require(value >= 0 && value < 1 shl 28) { "帧长度超出 ID3v2.4 可表示范围: $value" }
        return byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    private fun bigEndianBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun bigEndianLong(bytes: ByteArray, offset: Int, count: Int): Long {
        var value = 0L
        for (index in offset until minOf(bytes.size, offset + count)) {
            value = (value shl 8) or (bytes[index].toLong() and 0xFF)
        }
        return value
    }

    private fun synchsafeToInt(value: Long): Int =
        (((value ushr 24) and 0x7F).toInt() shl 21) or
            (((value ushr 16) and 0x7F).toInt() shl 14) or
            (((value ushr 8) and 0x7F).toInt() shl 7) or
            (value and 0x7F).toInt()

    private fun frameSize(major: Int, raw: Long, tagSize: Int): Int = when (major) {
        2 -> raw.toInt()
        3 -> if (raw in 0..tagSize.toLong()) raw.toInt() else synchsafeToInt(raw)
        else -> synchsafeToInt(raw)
    }

    private fun isUnsupported(major: Int, flags: Int): Boolean = when (major) {
        2 -> false
        3 -> flags and 0x00E0 != 0
        else -> flags and 0x004D != 0
    }

    private fun isUnsynchronised(major: Int, flags: Int): Boolean = when (major) {
        3 -> flags and 0x0080 != 0
        4 -> flags and 0x0002 != 0
        else -> false
    }

    private fun deunsynchronise(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size)
        var index = 0
        while (index < data.size) {
            val byte = data[index]
            output.write(byte.toInt())
            index++
            if (byte.toInt() and 0xFF == 0xFF && index < data.size && data[index].toInt() == 0) index++
        }
        return output.toByteArray()
    }

    private fun InputStream.readFully(count: Int): ByteArray? {
        if (count <= 0) return ByteArray(0)
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(buffer, offset, count - offset)
            if (read < 0) return null
            offset += read
        }
        return buffer
    }

    private fun ByteArray.indexOfZero(from: Int): Int? {
        for (index in from until size) {
            if (this[index].toInt() == 0) return index
        }
        return null
    }

    private fun utf16TerminatorEnd(data: ByteArray, from: Int): Int {
        var index = from
        while (index + 1 < data.size) {
            if (data[index].toInt() == 0 && data[index + 1].toInt() == 0) return index
            index += 2
        }
        return data.size
    }

    private fun decodeString(
        data: ByteArray,
        start: Int,
        encoding: Int,
        terminated: Boolean,
        defaultLittleEndian: Boolean = encoding == ENCODING_UTF16,
    ): DecodedString {
        var cursor = start.coerceIn(0, data.size)
        var littleEndian = defaultLittleEndian
        if (encoding == ENCODING_UTF16 && cursor + 1 < data.size) {
            val first = data[cursor].toInt() and 0xFF
            val second = data[cursor + 1].toInt() and 0xFF
            if (first == 0xFF && second == 0xFE) {
                littleEndian = true
                cursor += 2
            } else if (first == 0xFE && second == 0xFF) {
                littleEndian = false
                cursor += 2
            }
        }
        val wide = encoding == ENCODING_UTF16 || encoding == ENCODING_UTF16BE
        val end = when {
            !terminated -> data.size
            wide -> utf16TerminatorEnd(data, cursor)
            else -> data.indexOfZero(cursor) ?: data.size
        }
        val bytes = data.copyOfRange(minOf(cursor, end), end)
        val aligned = if (wide && bytes.size % 2 != 0) bytes.copyOf(bytes.size - 1) else bytes
        val text = when (encoding) {
            ENCODING_UTF16 -> String(aligned, if (littleEndian) UTF_16LE else UTF_16BE)
            ENCODING_UTF16BE -> String(aligned, UTF_16BE)
            3 -> String(aligned, UTF_8)
            else -> String(aligned, ISO_8859_1)
        }
        val terminatorLength = if (wide) 2 else 1
        return DecodedString(text, (end + terminatorLength).coerceAtMost(data.size), littleEndian)
    }

    private data class DecodedString(
        val text: String,
        val next: Int,
        val littleEndian: Boolean,
    )

    private data class Frame(
        val id: String,
        val flags: Int,
        val data: ByteArray,
    )

    private class FrameReader(
        private val stream: InputStream,
        private val major: Int,
        private val tagSize: Int,
    ) {
        private var consumed = 0

        fun next(): Frame? {
            val idLength = if (major == 2) 3 else 4
            if (consumed + idLength > tagSize) return null
            val idBytes = stream.readFully(idLength) ?: return null
            consumed += idLength
            val sizeBytes = stream.readFully(if (major == 2) 3 else 4) ?: return null
            consumed += sizeBytes.size
            var flags = 0
            if (major != 2) {
                val flagBytes = stream.readFully(2) ?: return null
                consumed += 2
                flags = ((flagBytes[0].toInt() and 0xFF) shl 8) or (flagBytes[1].toInt() and 0xFF)
            }
            val id = String(idBytes, ISO_8859_1)
            if (id.any { it.code == 0 }) return null
            val size = frameSize(major, bigEndianLong(sizeBytes, 0, sizeBytes.size), tagSize)
            if (size < 0 || consumed + size > tagSize) return null
            val data = stream.readFully(size) ?: return null
            consumed += size
            return Frame(id, flags, data)
        }
    }

    private class TagAccumulator {
        private var title: String? = null
        private var artist: String? = null
        private var album: String? = null
        private var date: String? = null
        private var artworkBytes: ByteArray? = null
        private var artworkMimeType: String? = null
        private var unsyncedLyrics: String? = null
        private var syncedLyrics: List<SyncedText> = emptyList()
        private var comment: String? = null

        fun accept(major: Int, id: String, data: ByteArray) {
            when (canonicalId(major, id)) {
                "TIT2" -> textOf(data)?.let { title = it }
                "TPE1" -> textOf(data)?.let { artist = it }
                "TALB" -> textOf(data)?.let { album = it }
                "TDRC" -> textOf(data)?.let { date = it }
                // COMM 的正文是：编码 + 语言3字节 + 短描述 + 空终止 + 正文
                "COMM" -> textOf(data, skipLanguage = true)?.let { comment = it }
                "APIC" -> acceptApic(major, data)
                "USLT" -> parseUnsyncedLyrics(data)?.let { unsyncedLyrics = it }
                "SYLT" -> syncedLyrics = parseSyncedLyrics(major, data)
            }
        }

        fun build(): Id3v2Tags = Id3v2Tags(
            title = title,
            artist = artist,
            album = album,
            date = date,
            artworkBytes = artworkBytes,
            artworkMimeType = artworkMimeType,
            unsyncedLyrics = unsyncedLyrics,
            syncedLyrics = syncedLyrics,
            comment = comment,
        )

        private fun acceptApic(major: Int, data: ByteArray) {
            if (data.isEmpty()) return
            var cursor = 1
            val mimeType = if (major == 2) {
                if (data.size < 5) return
                cursor = 4
                mimeFromFormat(String(data, 1, 3, ISO_8859_1))
            } else {
                val end = data.indexOfZero(cursor) ?: return
                val value = String(data, cursor, end - cursor, ISO_8859_1)
                cursor = end + 1
                value
            }
            if (mimeType.isBlank()) return
            if (cursor >= data.size) return
            cursor++
            val descriptor = decodeString(data, cursor, data[0].toInt() and 0xFF, terminated = true)
            val start = descriptor.next
            if (start >= data.size) return
            artworkMimeType = mimeType
            artworkBytes = data.copyOfRange(start, data.size)
        }

        private fun parseUnsyncedLyrics(data: ByteArray): String? {
            if (data.size < 5) return null
            val encoding = data[0].toInt() and 0xFF
            val descriptor = decodeString(data, 4, encoding, terminated = true)
            return decodeString(data, descriptor.next, encoding, terminated = false)
                .text
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        private fun parseSyncedLyrics(major: Int, data: ByteArray): List<SyncedText> {
            if (data.size < 6) return emptyList()
            val encoding = data[0].toInt() and 0xFF
            var cursor = 4 + (if (major == 2) 3 else 1) + 1
            val descriptor = decodeString(data, cursor, encoding, terminated = true)
            cursor = descriptor.next
            var littleEndian = descriptor.littleEndian
            val entries = mutableListOf<SyncedText>()
            while (cursor + 4 <= data.size) {
                val text = decodeString(data, cursor, encoding, terminated = true, littleEndian)
                cursor = text.next
                if (cursor + 4 > data.size) break
                entries += SyncedText(text.text, bigEndianLong(data, cursor, 4).toInt())
                cursor += 4
                littleEndian = text.littleEndian
            }
            return entries
        }
    }

    private fun textOf(data: ByteArray, skipLanguage: Boolean = false): String? {
        if (data.isEmpty()) return null
        val encoding = data[0].toInt() and 0xFF
        val start = if (skipLanguage) 4 else 1
        if (data.size <= start) return null
        return decodeString(data, start, encoding, terminated = true)
            .text
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun canonicalId(major: Int, id: String): String = when {
        major != 2 -> id
        id == "TT2" -> "TIT2"
        id == "TP1" -> "TPE1"
        id == "TAL" -> "TALB"
        id == "TYE" || id == "TDA" -> "TDRC"
        id == "PIC" -> "APIC"
        id == "ULT" -> "USLT"
        id == "SLT" -> "SYLT"
        else -> id
    }

    private fun mimeFromFormat(format: String): String = when (format.uppercase()) {
        "JPG" -> "image/jpeg"
        "PNG" -> "image/png"
        else -> "image/${format.lowercase()}"
    }
}

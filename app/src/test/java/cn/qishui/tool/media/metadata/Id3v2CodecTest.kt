package cn.qishui.tool.media.metadata

import cn.qishui.tool.domain.model.LyricLine
import cn.qishui.tool.domain.model.LyricWord
import cn.qishui.tool.domain.model.LyricsTrack
import cn.qishui.tool.domain.model.TrackMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_16LE

class Id3v2CodecTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val nullTerminator = byteArrayOf(0, 0)
    private val artwork = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46)

    private val lyrics = LyricsTrack(
        lines = listOf(
            LyricLine(
                text = "我在",
                startUs = 18_034_000L,
                endUs = 18_805_000L,
                words = listOf(
                    LyricWord("我", 18_034_000L, 18_365_000L),
                    LyricWord("在", 18_474_000L, 18_805_000L),
                ),
            ),
            LyricLine(
                text = "停留",
                startUs = 21_553_000L,
                endUs = 25_000_000L,
                words = listOf(
                    LyricWord("停", 21_553_000L, 22_500_000L),
                    LyricWord("留", 23_000_000L, 25_000_000L),
                ),
            ),
        ),
        offsetMs = 0L,
    )

    private val metadata = TrackMetadata(
        title = "一昭成名",
        artist = "深海鱼子酱",
        album = "起风了",
        year = "2017",
        artworkBytes = artwork,
        artworkMimeType = "image/jpeg",
    )

    @Test
    fun writesAndReadsBackEveryFrame() {
        val output = folder.newFile("export.mp3")

        Id3v2Codec.write(audioFile("source-full.bin"), output, metadata, lyrics)

        val tags = Id3v2Codec.read(output)
        assertEquals("一昭成名", tags.title)
        assertEquals("深海鱼子酱", tags.artist)
        assertEquals("起风了", tags.album)
        assertEquals("2017", tags.date)
        assertEquals("image/jpeg", tags.artworkMimeType)
        assertArrayEquals(artwork, tags.artworkBytes)
        assertEquals("我在\n停留", tags.unsyncedLyrics)
        assertEquals(
            listOf(
                SyncedText("我", 18_034),
                SyncedText("在", 18_474),
                SyncedText("停", 21_553),
                SyncedText("留", 23_000),
            ),
            tags.syncedLyrics,
        )
        assertEquals(Id3v2Codec.syncedEntries(lyrics), tags.syncedLyrics)
    }

    @Test
    fun omitsApicFrameWhenArtworkIsMissing() {
        val output = folder.newFile("no-cover.mp3")

        Id3v2Codec.write(
            audioFile("source-nocover.bin"),
            output,
            metadata.copy(artworkBytes = null, artworkMimeType = null),
            lyrics,
        )

        val tags = Id3v2Codec.read(output)
        assertNull(tags.artworkBytes)
        assertNull(tags.artworkMimeType)
        assertEquals("一昭成名", tags.title)
    }

    @Test
    fun omitsLyricFramesWhenTrackIsEmpty() {
        val output = folder.newFile("no-lyrics.mp3")

        Id3v2Codec.write(audioFile("source-nolyrics.bin"), output, metadata, null)

        val tags = Id3v2Codec.read(output)
        assertNull(tags.unsyncedLyrics)
        assertEquals(emptyList<SyncedText>(), tags.syncedLyrics)
        assertEquals("一昭成名", tags.title)
    }

    @Test
    fun keepsAudioPayloadIntactAfterLeadingTag() {
        val audio = ByteArray(1024) { (it % 251).toByte() }
        val output = folder.newFile("payload.mp3")

        Id3v2Codec.write(audioFile("source-payload.bin", audio), output, metadata, lyrics)

        val written = output.readBytes()
        assertEquals("ID3", String(written, 0, 3, ISO_8859_1))
        assertEquals(0x04, written[3].toInt())
        assertEquals(0x00, written[4].toInt())
        assertEquals(0x00, written[5].toInt())
        val tagSize = ((written[6].toInt() and 0x7F) shl 21) or
            ((written[7].toInt() and 0x7F) shl 14) or
            ((written[8].toInt() and 0x7F) shl 7) or
            (written[9].toInt() and 0x7F)
        assertEquals(10 + tagSize + audio.size, written.size)
        assertArrayEquals(audio, written.copyOfRange(10 + tagSize, written.size))
    }

    @Test
    fun returnsEmptyResultWhenNoTagIsPresent() {
        val untagged = folder.newFile("untagged.mp3").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 0x00))
        }

        val tags = Id3v2Codec.read(untagged)
        assertNull(tags.title)
        assertNull(tags.artist)
        assertNull(tags.album)
        assertNull(tags.date)
        assertNull(tags.artworkBytes)
        assertNull(tags.unsyncedLyrics)
        assertEquals(emptyList<SyncedText>(), tags.syncedLyrics)
    }

    @Test
    fun returnsEmptyResultForTagWithoutFrames() {
        val empty = folder.newFile("empty-tag.mp3").apply { writeBytes(tag(4, 0, ByteArray(0)) + byteArrayOf(1, 2, 3)) }

        assertNull(Id3v2Codec.read(empty).title)
    }

    @Test
    fun skipsUnknownFrames() {
        val body = frame("TIT2", textBody("未知帧测试")) +
            frame("XSOM", byteArrayOf(9, 8, 7, 6, 5)) +
            frame("PRIV", byteArrayOf(1, 2, 3)) +
            frame("TPE1", textBody("歌手甲"))
        val file = folder.newFile("unknown.mp3").apply { writeBytes(tag(4, 0, body) + byteArrayOf(1, 2, 3)) }

        val tags = Id3v2Codec.read(file)
        assertEquals("未知帧测试", tags.title)
        assertEquals("歌手甲", tags.artist)
    }

    @Test
    fun readsId3v23TagWithPlainFrameSize() {
        val body = framePlain("TIT2", textBody("v2.3 标题")) + framePlain("TALB", textBody("v2.3 专辑"))
        val file = folder.newFile("v23.mp3").apply { writeBytes(tag(3, 0, body) + byteArrayOf(1, 2, 3)) }

        val tags = Id3v2Codec.read(file)
        assertEquals("v2.3 标题", tags.title)
        assertEquals("v2.3 专辑", tags.album)
    }

    @Test
    fun readsId3v22TagWithShortIdentifiersAndSizes() {
        val body = frame22("TT2", textBody("v2.2 标题")) +
            frame22("ULT", usltBody("v2.2 歌词")) +
            frame22("SLT", syltBody(listOf(SyncedText("甲", 1_234), SyncedText("乙", 5_678)), 3)) +
            frame22("PIC", pic22Body(artwork))
        val file = folder.newFile("v22.mp3").apply { writeBytes(tag(2, 0, body) + byteArrayOf(1, 2, 3)) }

        val tags = Id3v2Codec.read(file)
        assertEquals("v2.2 标题", tags.title)
        assertEquals("v2.2 歌词", tags.unsyncedLyrics)
        assertEquals(listOf(SyncedText("甲", 1_234), SyncedText("乙", 5_678)), tags.syncedLyrics)
        assertEquals("image/jpeg", tags.artworkMimeType)
        assertArrayEquals(artwork, tags.artworkBytes)
    }

    @Test
    fun readsUnsynchronisedFrameFlag() {
        val body = frameWithFlags("TIT2", unsynchronise(textBody("aÿbÿ")), 0x0002)
        val file = folder.newFile("unsync.mp3").apply { writeBytes(tag(4, 0, body)) }

        assertEquals("aÿbÿ", Id3v2Codec.read(file).title)
    }

    @Test
    fun clampsSyncedTimestampsToNonDecreasingInBounds() {
        val unordered = LyricsTrack(
            lines = listOf(
                LyricLine("乱序", 0L, 9_000_000L, listOf(LyricWord("后", 5_000_000L, 6_000_000L))),
                LyricLine("负值", 0L, 1_000_000L, listOf(LyricWord("前", -4_000L, 900_000L))),
            ),
            offsetMs = 0L,
        )

        val entries = Id3v2Codec.syncedEntries(unordered)
        assertEquals(listOf(SyncedText("后", 5_000), SyncedText("前", 5_000)), entries)
        assertTrue(entries.zipWithNext().all { (first, second) -> first.timestampMs <= second.timestampMs })
        assertTrue(entries.all { it.timestampMs >= 0 })
    }

    @Test
    fun throwsStableExceptionWhenFileIsMissing() {
        val error = assertThrows(Id3Exception::class.java) {
            Id3v2Codec.read(File(folder.root, "absent.mp3"))
        }

        assertTrue(error.message?.contains("absent.mp3") == true)
    }

    private fun audioFile(name: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)): File =
        folder.newFile(name).apply { writeBytes(bytes) }

    private fun textBody(text: String): ByteArray = byteArrayOf(0x01) + bom + text.toByteArray(UTF_16LE)

    private fun usltBody(text: String): ByteArray = byteArrayOf(0x01) +
        "zho".toByteArray(ISO_8859_1) + bom + nullTerminator + bom + text.toByteArray(UTF_16LE)

    private fun syltBody(entries: List<SyncedText>, timestampFormatLength: Int = 1): ByteArray {
        val timestampFormat = if (timestampFormatLength == 3) byteArrayOf(0, 0, 0x02) else byteArrayOf(0x02)
        var body = byteArrayOf(0x01) + "zho".toByteArray(ISO_8859_1) + timestampFormat + byteArrayOf(0x01) + bom + nullTerminator
        for (entry in entries) {
            body += entry.text.toByteArray(UTF_16LE) + nullTerminator + bigEndian(entry.timestampMs)
        }
        return body
    }

    private fun unsynchronise(data: ByteArray): ByteArray {
        val output = ArrayList<Byte>(data.size * 2)
        for (byte in data) {
            output += byte
            if (byte.toInt() and 0xFF == 0xFF) output += 0
        }
        return output.toByteArray()
    }

    private fun pic22Body(bytes: ByteArray): ByteArray = byteArrayOf(0x01) +
        "JPG".toByteArray(ISO_8859_1) + byteArrayOf(0x03) + bom + nullTerminator + bytes

    private fun frame(id: String, body: ByteArray): ByteArray = id.toByteArray(ISO_8859_1) + synchsafe(body.size) + byteArrayOf(0, 0) + body

    private fun framePlain(id: String, body: ByteArray): ByteArray =
        id.toByteArray(ISO_8859_1) + bigEndian(body.size) + byteArrayOf(0, 0) + body

    private fun frameWithFlags(id: String, body: ByteArray, flags: Int): ByteArray =
        id.toByteArray(ISO_8859_1) + synchsafe(body.size) + byteArrayOf((flags ushr 8).toByte(), (flags and 0xFF).toByte()) + body

    private fun frame22(id: String, body: ByteArray): ByteArray = id.toByteArray(ISO_8859_1) +
        byteArrayOf((body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body

    private fun tag(major: Int, revision: Int, body: ByteArray): ByteArray = "ID3".toByteArray(ISO_8859_1) +
        byteArrayOf(major.toByte(), revision.toByte(), 0x00) + synchsafe(body.size) + body

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7F).toByte(),
        ((value ushr 14) and 0x7F).toByte(),
        ((value ushr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

    private fun bigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

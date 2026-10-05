package cn.music.audioworkshop.feature.edit.export

import cn.music.audioworkshop.domain.download.DownloadTarget
import cn.music.audioworkshop.domain.download.DownloadTargetResolver
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Export publish chain regression.
 *
 * Focus: once the file has been written to the target, a later failure while
 * resolving the final location must NOT delete it.
 *
 * Real device failure: SAF `findFile()` on a `SingleDocumentFile` throws
 * `UnsupportedOperationException`. That exception propagated into the catch block,
 * which deleted the just-written file -- the user saw "export succeeded" while the
 * download directory stayed empty.
 */
class ExportPublisherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeResolver(
        private val staging: File,
        val published: MutableMap<String, File> = mutableMapOf(),
        private val locationThrows: Boolean = false,
    ) : DownloadTargetResolver {
        override fun partTarget(taskId: String) = DownloadTarget.AppFile(File(staging, "$taskId.part").path)

        override fun finalTarget(fileName: String) = DownloadTarget.TreeDocument("tree:fake", fileName)

        override fun lengthOf(target: DownloadTarget) =
            (target as? DownloadTarget.AppFile)?.let { File(it.absolutePath).length() } ?: 0L

        override fun exists(target: DownloadTarget) =
            (target as DownloadTarget.TreeDocument).fileName in published

        override fun delete(target: DownloadTarget): Boolean {
            val t = target as DownloadTarget.TreeDocument
            return published.remove(t.fileName)?.delete() ?: true
        }

        override fun publish(part: DownloadTarget, final: DownloadTarget): Boolean {
            val source = File((part as DownloadTarget.AppFile).absolutePath)
            val t = final as DownloadTarget.TreeDocument
            if (t.fileName in published) return false
            val dest = File(staging, "published-${t.fileName}")
            source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            source.delete()
            published[t.fileName] = dest
            return true
        }

        override fun describe(target: DownloadTarget) = (target as DownloadTarget.TreeDocument).fileName

        override fun editableCopy(target: DownloadTarget): File? = null

        override fun publishedLocation(target: DownloadTarget): String {
            if (locationThrows) throw UnsupportedOperationException("SingleDocumentFile.listFiles")
            return (target as DownloadTarget.TreeDocument).fileName
        }
    }

    private fun tempExport(name: String, bytes: Int = 2048): File {
        val file = folder.newFile(name)
        file.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        return file
    }

    @Test
    fun `publish succeeds and file stays in target`() = runBlocking {
        val publisher = ExportPublisher(FakeResolver(folder.root))

        val result = publisher.publish(tempExport("a.flac").absolutePath, "song.flac")

        assertEquals(2048L, result.bytes)
    }

@Test
    fun `failing to resolve location must not delete the written file`() = runBlocking {
        val resolver = FakeResolver(folder.root, locationThrows = true)
        val publisher = ExportPublisher(resolver)

        // 取位置失败现在被降级成 describe()，不再上抛 ——
        // 上抛会进 catch 把刚写好的文件删掉（真机故障）。
        // 所以这里期望的是「发布成功 + 文件还在」，而不是「抛异常」。
        val result = runCatching { publisher.publish(tempExport("b.flac").absolutePath, "song.flac") }

        assertTrue("取位置失败不该让整个发布失败", result.isSuccess)
        assertTrue(
            "regression: file reached the target, location lookup failure must not delete it",
            "song.flac" in resolver.published,
        )
        assertTrue("文件内容应完整", resolver.published.getValue("song.flac").length() == 2048L)
    }

    @Test
    fun `location lookup failure falls back to describe`() = runBlocking {
        val resolver = FakeResolver(folder.root, locationThrows = true)
        val publisher = ExportPublisher(resolver)

        val result = publisher.publish(tempExport("d.flac").absolutePath, "song.flac")

        assertEquals("降级后应回落到 describe()", "song.flac", result.location)
        assertEquals(2048L, result.bytes)
    }

    @Test
    fun `zero byte output is a failure and leaves nothing behind`() = runBlocking {
        val resolver = FakeResolver(folder.root)
        val publisher = ExportPublisher(resolver)
        val empty = tempExport("c.flac", bytes = 0)

        val result = runCatching { publisher.publish(empty.absolutePath, "empty.flac") }

        assertTrue(result.isFailure)
        assertTrue("0 bytes must not count as published", "empty.flac" !in resolver.published)
    }

    @Test
    fun `missing source file reports failure`() = runBlocking {
        val publisher = ExportPublisher(FakeResolver(folder.root))
        val missing = File(folder.root, "nope.flac")

        val result = runCatching { publisher.publish(missing.absolutePath, "missing.flac") }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }
}

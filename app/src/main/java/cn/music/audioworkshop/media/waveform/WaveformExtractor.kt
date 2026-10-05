package cn.music.audioworkshop.media.waveform

import android.os.SystemClock
import cn.music.audioworkshop.domain.model.WaveformPeaks
import cn.music.audioworkshop.domain.waveform.WaveformSource
import cn.music.audioworkshop.media.pcm.PcmChunkReader
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class WaveformExtractor(
    private val chunkReader: PcmChunkReader,
    private val durationUsOf: (File) -> Long,
) : WaveformSource {

    override suspend fun peaks(
        sourceTrackId: String,
        file: File,
        onPartial: (WaveformPeaks) -> Unit,
    ): WaveformPeaks = withContext(Dispatchers.IO) {
        val key = cacheKey(sourceTrackId, file)
        WaveformCache.get(key)?.let { cached ->
            onPartial(cached)
            return@withContext cached
        }
        val durationUs = durationUsOf(file)
        val accumulator = WaveformBucketAccumulator(WaveformBuckets.countFor(durationUs), durationUs)
        val context: CoroutineContext = coroutineContext
        // 每 PARTIAL_INTERVAL_MS 抛一次已解出的部分，界面就能立刻画出来。
        var lastEmitAt = SystemClock.elapsedRealtime()
        chunkReader.read(file) { chunk ->
            context.ensureActive()
            accumulator.accept(chunk.presentationTimeUs, chunk.sampleRateHz, chunk.channels, chunk.samples)
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmitAt >= PARTIAL_INTERVAL_MS) {
                lastEmitAt = now
                onPartial(accumulator.build(0L, durationUs))
            }
        }
        accumulator.build(0L, durationUs).also {
            WaveformCache.put(key, it)
            onPartial(it)
        }
    }

    private companion object {
        const val PARTIAL_INTERVAL_MS = 250L
    }

    private fun cacheKey(sourceTrackId: String, file: File): String =
        "$sourceTrackId|${file.length()}|${file.lastModified()}"
}

private object WaveformCache {

    private const val MAX_ENTRIES = 5

    private val entries = object : LinkedHashMap<String, WaveformPeaks>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WaveformPeaks>?): Boolean =
            size > MAX_ENTRIES
    }

    fun get(key: String): WaveformPeaks? = synchronized(entries) { entries[key] }

    fun put(key: String, peaks: WaveformPeaks) {
        synchronized(entries) { entries[key] = peaks }
    }
}

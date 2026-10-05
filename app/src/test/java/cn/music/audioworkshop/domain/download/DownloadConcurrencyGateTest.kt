package cn.music.audioworkshop.domain.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConcurrencyGateTest {

    @Test
    fun runsUpToTheConfiguredLimitInParallel() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = this, limit = 2)
        val release = CompletableDeferred<Unit>()
        val bothBusy = CompletableDeferred<Unit>()
        val live = java.util.concurrent.atomic.AtomicInteger(0)
        var peak = 0

        val jobs = (1..5).map {
            async {
                gate.withPermit {
                    if (live.incrementAndGet() == 2) bothBusy.complete(Unit)
                    peak = maxOf(peak, live.get())
                    release.await()
                    live.decrementAndGet()
                }
            }
        }
        withTimeout(5_000L) { bothBusy.await() }
        assertEquals(2, peak)
        assertEquals(2, gate.activeCount)
        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(2, peak)
        assertEquals(0, gate.activeCount)
    }

    @Test
    fun queuedWorkStartsAfterAPermitIsReleased() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = this, limit = 1)
        val order = mutableListOf<Int>()
        val firstStarted = CompletableDeferred<Unit>()

        val first = async { gate.withPermit { firstStarted.complete(Unit); delay(60) } }
        firstStarted.await()
        val second = async { gate.withPermit { order += 2 } }
        delay(20)
        first.await()
        second.await()
        assertEquals(listOf(2), order)
    }

    @Test
    fun raisingTheLimitReleasesWaiters() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = this, limit = 1)
        val started = mutableListOf<Int>()
        val keepAlive = CompletableDeferred<Unit>()

        val holding = async { gate.withPermit { keepAlive.await() } }
        val queued = (1..3).map { index -> async { gate.withPermit { started += index } } }
        delay(40)
        assertEquals(emptyList<Int>(), started)

        gate.updateLimit(3)
        delay(40)
        assertEquals(listOf(1, 2, 3), started.sorted())
        keepAlive.complete(Unit)
        holding.await()
        queued.forEach { it.await() }
    }

    @Test
    fun limitIsClampedIntoTheSupportedRange() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = this, limit = 9)
        assertEquals(4, gate.currentLimit)
        gate.updateLimit(0)
        assertEquals(1, gate.currentLimit)
    }

    @Test
    fun permitsAreReleasedWhenTheBlockFails() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = this, limit = 1)
        runCatching { gate.withPermit { error("boom") } }
        assertEquals(0, gate.activeCount)
        val reached = withTimeoutOrNull(1_000L) { gate.withPermit { true } }
        assertTrue(reached == true)
        assertFalse(gate.activeCount > 0)
    }

    @Test
    fun permitsAreReleasedWhenTheCallerIsCancelled() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = this, limit = 1)
        val started = CompletableDeferred<Unit>()
        val cancelled = async {
            gate.withPermit {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
            }
        }
        started.await()
        cancelled.cancelAndJoin()
        assertEquals(0, gate.activeCount)
        val reached = withTimeoutOrNull(1_000L) { gate.withPermit { true } }
        assertTrue(reached == true)
    }

    @Test
    fun cancelledWaitersDoNotBlockLaterCallers() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = this, limit = 1)
        val started = CompletableDeferred<Unit>()
        val keepAlive = CompletableDeferred<Unit>()
        val holding = async { gate.withPermit { started.complete(Unit); keepAlive.await() } }
        started.await()
        val queued = async { runCatching { gate.withPermit { } } }
        delay(30)
        queued.cancelAndJoin()
        keepAlive.complete(Unit)
        holding.await()
        assertEquals(0, gate.activeCount)
        val reached = withTimeoutOrNull(1_000L) { gate.withPermit { true } }
        assertTrue(reached == true)
    }

    @Test
    fun concurrentCallersNeverExceedTheLimit() = runBlocking {
        val gate = DownloadConcurrencyGate(scope = CoroutineScope(Dispatchers.Unconfined), limit = 3)
        val release = CompletableDeferred<Unit>()
        val allBusy = CompletableDeferred<Unit>()
        val live = java.util.concurrent.atomic.AtomicInteger(0)
        var peak = 0
        val jobs = (1..20).map {
            async(Dispatchers.Default) {
                gate.withPermit {
                    if (live.incrementAndGet() == 3) allBusy.complete(Unit)
                    peak = maxOf(peak, live.get())
                    release.await()
                    live.decrementAndGet()
                }
            }
        }
        // ponytail: 等条件而不是等时间，慢机器上也不会误报
        withTimeout(5_000L) { allBusy.await() }
        assertEquals(3, peak)
        release.complete(Unit)
        jobs.forEach { it.await() }
        assertEquals(0, gate.activeCount)
        assertEquals(0, live.get())
    }
}

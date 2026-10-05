package cn.music.audioworkshop.domain.download

import cn.music.audioworkshop.domain.model.MAX_CONNECTIONS
import cn.music.audioworkshop.domain.model.MIN_CONNECTIONS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DownloadConcurrencyGate(
    private val scope: CoroutineScope,
    limit: Int = 3,
) {
    private val mutex = Mutex()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var active = 0
    private var permits = limit.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)

    val currentLimit: Int get() = permits

    val activeCount: Int get() = active

    fun updateLimit(value: Int) {
        permits = value.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)
        scope.launch {
            mutex.withLock { wakeUpTo(permits) }
        }
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            // ponytail: NonCancellable — 释放许可必须完成，否则取消会永久泄漏名额
            withContext(NonCancellable) {
                mutex.withLock {
                    active--
                    wakeUpTo(permits)
                }
            }
        }
    }

    private suspend fun acquire() {
        while (true) {
            val waiter = mutex.withLock {
                if (active < permits) {
                    active++
                    null
                } else {
                    CompletableDeferred<Unit>().also { waiters.addLast(it) }
                }
            }
            if (waiter == null) return
            try {
                waiter.await()
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock { waiters.remove(waiter) }
                }
                throw error
            }
        }
    }

    private fun wakeUpTo(limit: Int) {
        while (active < limit && waiters.isNotEmpty()) {
            waiters.removeFirst().complete(Unit)
        }
    }
}

package io.bluetape4k.leader.internal

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.bluetape4k.support.requirePositiveNumber
import kotlin.time.Duration

/** acquire, cleanup, watchdog 인접 연산이 공유하는 bounded executor입니다. */
class LeaseOperationScheduler(
    maxInFlight: Int,
    queueCapacity: Int,
    threadNamePrefix: String = "bluetape4k-leader-lease",
) : AutoCloseable {

    private companion object {
        const val IDLE_POLL_MILLIS = 1L
        const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }

    private val running = AtomicInteger()
    private val outstanding = AtomicInteger()
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        maxInFlight.requirePositiveNumber("maxInFlight"),
        maxInFlight,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity.requirePositiveNumber("queueCapacity")),
        ThreadFactory { task ->
            Thread(task, "$threadNamePrefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

    val inFlight: Int get() = running.get()
    val queued: Int get() = executor.queue.size
    val queueCapacity: Int get() = executor.queue.remainingCapacity() + executor.queue.size
    val isShutdown: Boolean get() = executor.isShutdown

    fun <T> submit(task: () -> T): Future<T>? {
        if (executor.isShutdown) return null
        outstanding.incrementAndGet()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val tracked = object : FutureTask<T>(Callable {
            started.set(true)
            running.incrementAndGet()
            try {
                task()
            } finally {
                running.decrementAndGet()
                outstanding.decrementAndGet()
            }
        }) {
            override fun done() {
                if (isCancelled && !started.get()) {
                    outstanding.decrementAndGet()
                }
            }
        }
        return try {
            executor.execute(tracked)
            tracked
        } catch (_: RejectedExecutionException) {
            outstanding.decrementAndGet()
            null
        }
    }

    @Suppress("ReturnCount")
    fun awaitIdle(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds.coerceAtLeast(0L)
        while (System.nanoTime() < deadline) {
            if (outstanding.get() == 0 && queued == 0) return true
            try {
                Thread.sleep(IDLE_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return outstanding.get() == 0 && queued == 0
    }

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}

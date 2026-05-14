package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.NoopLeaderHistorySink
import io.bluetape4k.leader.micrometer.MicrometerNames
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.time.Instant

class MicrometerSafeLeaderHistoryRecorderTest {

    companion object : KLogging()

    private val now = Instant.parse("2026-05-14T10:00:00Z")

    private fun record() = LeaderLockHistoryRecord(
        lockName = "test-lock",
        token = "tok-abc",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = now,
        lockedUntil = now.plusSeconds(60),
    )

    private val key = LeaderHistoryKey(lockName = "test-lock", token = "tok-abc")

    private fun failureCount(registry: SimpleMeterRegistry, sinkName: String): Double =
        registry.counter(MicrometerNames.HISTORY_SINK_FAILURES, "sink", sinkName).count()

    private fun acquireMissingCount(registry: SimpleMeterRegistry, sinkName: String): Double =
        registry.counter(MicrometerNames.HISTORY_ACQUIRE_MISSING, "sink", sinkName).count()

    // ── acquire missing counter ───────────────────────────────────────────

    @Test
    fun `recordAcquired increments acquire-missing counter when sink returns null`() {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerSafeLeaderHistoryRecorder(NoopLeaderHistorySink, registry)
        recorder.recordAcquired(record())
        assertEquals(1.0, acquireMissingCount(registry, "NoopLeaderHistorySink"))
    }

    @Test
    fun `recordAcquired does not increment acquire-missing counter when key returned`() {
        val registry = SimpleMeterRegistry()
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord) = key
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSafeLeaderHistoryRecorder(sink, registry)
        recorder.recordAcquired(record())
        assertEquals(0.0, acquireMissingCount(registry, sink::class.simpleName ?: "unknown"))
    }

    // ── failure counter ───────────────────────────────────────────────────

    @Test
    fun `recordAcquired increments failure counter when sink throws Exception`() {
        val registry = SimpleMeterRegistry()
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw RuntimeException("storage down")
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSafeLeaderHistoryRecorder(sink, registry)
        val result = recorder.recordAcquired(record())
        assertNull(result)
        assertEquals(1.0, failureCount(registry, sink::class.simpleName ?: "unknown"))
    }

    @Test
    fun `recordCompleted increments failure counter when sink throws Exception`() {
        val registry = SimpleMeterRegistry()
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord) = null
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) =
                throw RuntimeException("write failed")
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSafeLeaderHistoryRecorder(sink, registry)
        recorder.recordCompleted(key, now, 100L)
        assertEquals(1.0, failureCount(registry, sink::class.simpleName ?: "unknown"))
    }

    // ── CancellationException: not counted, rethrown ──────────────────────

    @Test
    fun `recordAcquired does not increment counter on CancellationException and rethrows`() {
        val registry = SimpleMeterRegistry()
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw CancellationException("cancelled")
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSafeLeaderHistoryRecorder(sink, registry)
        assertFailsWith<CancellationException> {
            recorder.recordAcquired(record())
        }
        assertEquals(0.0, failureCount(registry, sink::class.simpleName ?: "unknown"))
    }
}

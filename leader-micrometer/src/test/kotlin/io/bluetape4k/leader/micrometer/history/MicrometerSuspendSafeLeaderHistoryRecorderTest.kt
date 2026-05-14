package io.bluetape4k.leader.micrometer.history

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.NoopSuspendLeaderHistorySink
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import io.bluetape4k.leader.micrometer.MicrometerNames
import io.bluetape4k.logging.KLogging
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant

class MicrometerSuspendSafeLeaderHistoryRecorderTest {

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
    fun `recordAcquired increments acquire-missing counter when sink returns null`() = runTest {
        val registry = SimpleMeterRegistry()
        val recorder = MicrometerSuspendSafeLeaderHistoryRecorder(NoopSuspendLeaderHistorySink, registry)
        recorder.recordAcquired(record())
        acquireMissingCount(registry, "NoopSuspendLeaderHistorySink") shouldBeEqualTo 1.0
    }

    @Test
    fun `recordAcquired does not increment acquire-missing counter when key returned`() = runTest {
        val registry = SimpleMeterRegistry()
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord) = key
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSuspendSafeLeaderHistoryRecorder(sink, registry)
        recorder.recordAcquired(record())
        acquireMissingCount(registry, sink::class.simpleName ?: "unknown") shouldBeEqualTo 0.0
    }

    // ── failure counter ───────────────────────────────────────────────────

    @Test
    fun `recordAcquired increments failure counter when sink throws Exception`() = runTest {
        val registry = SimpleMeterRegistry()
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw RuntimeException("storage down")
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSuspendSafeLeaderHistoryRecorder(sink, registry)
        val result = recorder.recordAcquired(record())
        result.shouldBeNull()
        failureCount(registry, sink::class.simpleName ?: "unknown") shouldBeEqualTo 1.0
    }

    @Test
    fun `recordCompleted increments failure counter when sink throws Exception`() = runTest {
        val registry = SimpleMeterRegistry()
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord) = null
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) =
                throw RuntimeException("write failed")
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSuspendSafeLeaderHistoryRecorder(sink, registry)
        recorder.recordCompleted(key, now, 100L)
        failureCount(registry, sink::class.simpleName ?: "unknown") shouldBeEqualTo 1.0
    }

    // ── CancellationException: not counted, rethrown ──────────────────────

    @Test
    fun `recordAcquired does not increment counter on CancellationException and rethrows`() = runTest {
        val registry = SimpleMeterRegistry()
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw CancellationException("cancelled")
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSuspendSafeLeaderHistoryRecorder(sink, registry)
        assertFailsWith<CancellationException> {
            recorder.recordAcquired(record())
        }
        failureCount(registry, sink::class.simpleName ?: "unknown") shouldBeEqualTo 0.0
    }

    @Test
    fun `recordCompleted does not increment counter on CancellationException and rethrows`() = runTest {
        val registry = SimpleMeterRegistry()
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord) = null
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) =
                throw CancellationException("cancelled")
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = MicrometerSuspendSafeLeaderHistoryRecorder(sink, registry)
        assertFailsWith<CancellationException> {
            recorder.recordCompleted(key, now, 100L)
        }
        failureCount(registry, sink::class.simpleName ?: "unknown") shouldBeEqualTo 0.0
    }
}

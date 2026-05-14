package io.bluetape4k.leader.history

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SuspendSafeLeaderHistoryRecorderTest {

    companion object : KLogging()

    private val now = java.time.Instant.parse("2026-05-14T10:00:00Z")
    private val future = now.plusSeconds(60)

    private fun record() = LeaderLockHistoryRecord(
        lockName = "test-lock",
        token = "tok-abc",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = now,
        lockedUntil = future,
    )

    private val key = LeaderHistoryKey(lockName = "test-lock", token = "tok-abc")

    // ── successful sink ───────────────────────────────────────────────────

    @Test
    fun `recordAcquired returns key on success`() = runTest {
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord) = key
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long) = Unit
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SuspendSafeLeaderHistoryRecorder(sink)
        val result = recorder.recordAcquired(record())
        assertNotNull(result)
    }

    @Test
    fun `recordCompleted does not throw when sink succeeds`() = runTest {
        val recorder = SuspendSafeLeaderHistoryRecorder(NoopSuspendLeaderHistorySink)
        recorder.recordCompleted(key, now, 100L)
    }

    @Test
    fun `recordFailed does not throw when sink succeeds`() = runTest {
        val recorder = SuspendSafeLeaderHistoryRecorder(NoopSuspendLeaderHistorySink)
        recorder.recordFailed(key, now, 100L, null)
    }

    // ── fault isolation ───────────────────────────────────────────────────

    @Test
    fun `recordAcquired swallows sink Exception and returns null`() = runTest {
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw RuntimeException("storage unavailable")
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long) = Unit
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SuspendSafeLeaderHistoryRecorder(sink)
        val result = recorder.recordAcquired(record())
        assertNull(result)
    }

    @Test
    fun `recordCompleted swallows sink Exception`() = runTest {
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = null
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long) =
                throw RuntimeException("storage error")
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SuspendSafeLeaderHistoryRecorder(sink)
        recorder.recordCompleted(key, now, 100L) // must not throw
    }

    @Test
    fun `recordFailed with CancellationException logs warning but does not rethrow`() = runTest {
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = null
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long) = Unit
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SuspendSafeLeaderHistoryRecorder(sink)
        // CancellationException passed as `error` arg to recordFailed — must not rethrow
        recorder.recordFailed(key, now, 100L, CancellationException("cancelled"))
    }

    // ── CancellationException rethrow in sink calls ───────────────────────

    @Test
    fun `recordAcquired rethrows CancellationException from sink`() = runTest {
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw CancellationException("cancelled in sink")
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long) = Unit
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SuspendSafeLeaderHistoryRecorder(sink)
        assertFailsWith<CancellationException> {
            recorder.recordAcquired(record())
        }
    }

    @Test
    fun `recordCompleted rethrows CancellationException from sink`() = runTest {
        val sink = object : SuspendLeaderHistorySink {
            override suspend fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? = null
            override suspend fun recordCompleted(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long) =
                throw CancellationException("cancelled in sink")
            override suspend fun recordFailed(key: LeaderHistoryKey, finishedAt: java.time.Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SuspendSafeLeaderHistoryRecorder(sink)
        assertFailsWith<CancellationException> {
            recorder.recordCompleted(key, now, 100L)
        }
    }
}

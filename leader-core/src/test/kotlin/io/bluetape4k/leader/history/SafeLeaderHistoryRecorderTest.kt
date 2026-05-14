package io.bluetape4k.leader.history

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.logging.KLogging
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import java.time.Instant

class SafeLeaderHistoryRecorderTest {

    companion object : KLogging()

    private val now = Instant.parse("2026-05-14T10:00:00Z")
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
    fun `recordAcquired returns key from sink on success`() {
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord) = key
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SafeLeaderHistoryRecorder(sink)
        val result = recorder.recordAcquired(record())
        result.shouldNotBeNull()
    }

    @Test
    fun `recordCompleted does not throw when sink succeeds`() {
        val recorder = SafeLeaderHistoryRecorder(NoopLeaderHistorySink)
        recorder.recordCompleted(key, now, 100L)
    }

    @Test
    fun `recordFailed does not throw when sink succeeds`() {
        val recorder = SafeLeaderHistoryRecorder(NoopLeaderHistorySink)
        recorder.recordFailed(key, now, 100L, null)
    }

    // ── fault isolation ───────────────────────────────────────────────────

    @Test
    fun `recordAcquired returns null and swallows when sink throws Exception`() {
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw RuntimeException("storage down")
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SafeLeaderHistoryRecorder(sink)
        val result = recorder.recordAcquired(record())
        result.shouldBeNull()
    }

    @Test
    fun `recordCompleted swallows Exception from sink`() {
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord) = null
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) =
                throw RuntimeException("write failed")
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SafeLeaderHistoryRecorder(sink)
        recorder.recordCompleted(key, now, 100L) // must not throw
    }

    @Test
    fun `recordFailed swallows Exception from sink`() {
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord) = null
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) =
                throw RuntimeException("write failed")
        }
        val recorder = SafeLeaderHistoryRecorder(sink)
        recorder.recordFailed(key, now, 100L, RuntimeException("action error"))
    }

    // ── CancellationException rethrow ─────────────────────────────────────

    @Test
    fun `recordAcquired rethrows CancellationException from sink`() {
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord): LeaderHistoryKey? =
                throw CancellationException("cancelled")
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) = Unit
        }
        val recorder = SafeLeaderHistoryRecorder(sink)
        assertFailsWith<CancellationException> {
            recorder.recordAcquired(record())
        }
    }

    // ── errorType / errorMessage extraction ───────────────────────────────

    @Test
    fun `recordFailed extracts errorType and truncates errorMessage`() {
        var capturedType: String? = null
        var capturedMsg: String? = null
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord) = null
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) {
                capturedType = errorType
                capturedMsg = errorMessage
            }
        }
        val recorder = SafeLeaderHistoryRecorder(sink)
        val cause = IllegalStateException("bad state")
        recorder.recordFailed(key, now, 50L, cause)

        capturedType?.contains("IllegalStateException") shouldBeEqualTo true
        capturedMsg shouldBeEqualTo "bad state"
    }

    @Test
    fun `recordFailed handles null error gracefully`() {
        var capturedType: String? = "sentinel"
        val sink = object : LeaderHistorySink {
            override fun recordAcquired(record: LeaderLockHistoryRecord) = null
            override fun recordCompleted(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long) = Unit
            override fun recordFailed(key: LeaderHistoryKey, finishedAt: Instant, durationMs: Long, errorType: String?, errorMessage: String?) {
                capturedType = errorType
            }
        }
        val recorder = SafeLeaderHistoryRecorder(sink)
        recorder.recordFailed(key, now, 50L, null)
        capturedType.shouldBeNull()
    }
}

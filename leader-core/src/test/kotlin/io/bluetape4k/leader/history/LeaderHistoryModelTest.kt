package io.bluetape4k.leader.history

import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant

class LeaderHistoryModelTest {

    companion object : KLogging()

    private val now = Instant.parse("2026-05-14T10:00:00Z")
    private val future = now.plusSeconds(60)
    private val past = now.minusSeconds(1)

    private fun baseRecord(
        lockedUntil: Instant = future,
        status: LeaderHistoryStatus? = null,
    ) = LeaderLockHistoryRecord(
        lockName = "test-lock",
        token = "tok-abc",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = now,
        lockedUntil = lockedUntil,
        status = status,
    )

    // ── LeaderLockHistoryRecord ────────────────────────────────────────────

    @Test
    fun `factory rejects blank lockName`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderLockHistoryRecord(
                lockName = "  ",
                token = "tok",
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = now,
                lockedUntil = future,
            )
        }
    }

    @Test
    fun `factory rejects blank token`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderLockHistoryRecord(
                lockName = "lock",
                token = "",
                kind = LockIdentity.AnnotationKind.SINGLE,
                acquiredAt = now,
                lockedUntil = future,
            )
        }
    }

    @Test
    fun `factory defensively copies metadata`() {
        val mutable = mutableMapOf("k" to "v")
        val record = LeaderLockHistoryRecord(
            lockName = "lock",
            token = "tok",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = now,
            lockedUntil = future,
            metadata = mutable,
        )
        mutable["extra"] = "injected"
        assertFalse(record.metadata.containsKey("extra"), "metadata must be defensively copied")
    }

    // ── LeaderHistoryKey ──────────────────────────────────────────────────

    @Test
    fun `LeaderHistoryKey rejects blank lockName`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderHistoryKey(lockName = "", token = "tok")
        }
    }

    @Test
    fun `LeaderHistoryKey rejects blank token`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderHistoryKey(lockName = "lock", token = "   ")
        }
    }

    // ── effectiveStatus ───────────────────────────────────────────────────

    @Test
    fun `effectiveStatus returns terminal status when set`() {
        val record = baseRecord(status = LeaderHistoryStatus.COMPLETED)
        assertEquals(LeaderHistoryStatus.COMPLETED, record.effectiveStatus(now))
    }

    @Test
    fun `effectiveStatus returns ACQUIRED when no status and not expired`() {
        val record = baseRecord(lockedUntil = future, status = null)
        assertEquals(LeaderHistoryStatus.ACQUIRED, record.effectiveStatus(now))
    }

    @Test
    fun `effectiveStatus returns EXPIRED when lockedUntil is before now`() {
        val record = baseRecord(lockedUntil = past, status = null)
        assertEquals(LeaderHistoryStatus.EXPIRED, record.effectiveStatus(now))
    }

    @Test
    fun `effectiveStatus with FAILED terminal status ignores lockedUntil`() {
        val record = baseRecord(lockedUntil = past, status = LeaderHistoryStatus.FAILED)
        assertEquals(LeaderHistoryStatus.FAILED, record.effectiveStatus(now))
    }

    // ── NoopLeaderHistorySink ─────────────────────────────────────────────

    @Test
    fun `NoopLeaderHistorySink recordAcquired returns null`() {
        val key = NoopLeaderHistorySink.recordAcquired(baseRecord())
        assertNull(key)
    }

    @Test
    fun `NoopLeaderHistorySink recordCompleted and recordFailed are no-ops`() {
        val key = LeaderHistoryKey(lockName = "lock", token = "tok")
        NoopLeaderHistorySink.recordCompleted(key, now, 100L)
        NoopLeaderHistorySink.recordFailed(key, now, 100L, null, null)
    }
}

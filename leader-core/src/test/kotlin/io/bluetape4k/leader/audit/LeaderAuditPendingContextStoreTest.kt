package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.audit.internal.LeaderAuditPendingContextStore
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.junit.jupiter.api.Test
import java.time.Instant

class LeaderAuditPendingContextStoreTest {

    @Test
    fun `store removes by token fingerprint without retaining token in context`() {
        val store = LeaderAuditPendingContextStore(maxEntries = 1)
        val key = LeaderHistoryKey(lockName = "job", token = "credential")
        store.put(key, record())

        store.size() shouldBeEqualTo 1
        val context = store.remove(key)
        context?.lockName shouldBeEqualTo "job"
        context.toString().contains("credential").not().shouldBeTrue()
        store.size() shouldBeEqualTo 0
    }

    @Test
    fun `bounded capacity evicts oldest acquisition context`() {
        val store = LeaderAuditPendingContextStore(maxEntries = 1)
        val first = LeaderHistoryKey(lockName = "first", token = "one")
        val second = LeaderHistoryKey(lockName = "second", token = "two")
        store.put(first, record("first"))
        store.put(second, record("second"))

        store.remove(first).shouldBeEqualTo(null)
        store.remove(second)?.lockName shouldBeEqualTo "second"
    }

    private fun record(lockName: String = "job"): LeaderLockHistoryRecord = LeaderLockHistoryRecord(
        lockName = lockName,
        token = "credential",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = Instant.parse("2026-08-19T00:00:00Z"),
        lockedUntil = Instant.parse("2026-08-19T00:01:00Z"),
        status = LeaderHistoryStatus.ACQUIRED,
    )
}

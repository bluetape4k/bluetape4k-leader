package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
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

    @Test
    fun `storage bounds metadata cardinality and string lengths`() {
        val metadata = linkedMapOf<String, String>().apply {
            put("a".repeat(100), "v".repeat(1000))
            repeat(LeaderLockHistoryRecord.MAX_METADATA_KEYS) { index ->
                put("entry-$index", "value-$index")
            }
        }
        val key = LeaderHistoryKey(lockName = "bounded", token = "token")
        val store = LeaderAuditPendingContextStore()

        store.put(key, record(metadata = metadata))

        val stored = requireNotNull(store.remove(key))
        stored.metadata.size shouldBeEqualTo LeaderLockHistoryRecord.MAX_METADATA_KEYS
        val boundedEntry = stored.metadata.entries.single { it.key.length == 64 }
        boundedEntry.value.length shouldBeEqualTo LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH
    }

    @Test
    fun `storage bounds total metadata utf8 bytes deterministically`() {
        val metadata = linkedMapOf<String, String>().apply {
            repeat(LeaderLockHistoryRecord.MAX_METADATA_KEYS) { index ->
                put("entry-$index", "가".repeat(LeaderLockHistoryRecord.MAX_METADATA_VALUE_LENGTH))
            }
        }
        val firstKey = LeaderHistoryKey(lockName = "first", token = "token-1")
        val secondKey = LeaderHistoryKey(lockName = "second", token = "token-2")
        val firstStore = LeaderAuditPendingContextStore()
        val secondStore = LeaderAuditPendingContextStore()

        firstStore.put(firstKey, record(metadata = metadata))
        secondStore.put(secondKey, record(metadata = metadata))

        val first = requireNotNull(firstStore.remove(firstKey))
        val second = requireNotNull(secondStore.remove(secondKey))
        val firstBytes = first.metadata.entries.sumOf { (key, value) ->
            key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
        }

        firstBytes shouldBeLessOrEqualTo LeaderAuditExportEvent.MAX_ATTRIBUTES_TOTAL_BYTES
        (first.metadata.size < LeaderLockHistoryRecord.MAX_METADATA_KEYS).shouldBeTrue()
        first.metadata shouldBeEqualTo second.metadata
    }

    @Test
    fun `storage metadata bounds are independent of input iteration order`() {
        val ascending = linkedMapOf<String, String>().apply {
            repeat(LeaderLockHistoryRecord.MAX_METADATA_KEYS + 4) { index ->
                put("entry-$index", "value-$index")
            }
        }
        val descending = ascending.entries.reversed().associateTo(linkedMapOf()) { it.key to it.value }
        val firstKey = LeaderHistoryKey(lockName = "ascending", token = "token-1")
        val secondKey = LeaderHistoryKey(lockName = "descending", token = "token-2")
        val firstStore = LeaderAuditPendingContextStore()
        val secondStore = LeaderAuditPendingContextStore()

        firstStore.put(firstKey, record(metadata = ascending))
        secondStore.put(secondKey, record(metadata = descending))

        firstStore.remove(firstKey)?.metadata shouldBeEqualTo secondStore.remove(secondKey)?.metadata
    }

    private fun record(
        lockName: String = "job",
        metadata: Map<String, String> = emptyMap(),
    ): LeaderLockHistoryRecord = LeaderLockHistoryRecord(
        lockName = lockName,
        token = "credential",
        kind = LockIdentity.AnnotationKind.SINGLE,
        acquiredAt = Instant.parse("2026-08-19T00:00:00Z"),
        lockedUntil = Instant.parse("2026-08-19T00:01:00Z"),
        status = LeaderHistoryStatus.ACQUIRED,
        metadata = metadata,
    )
}

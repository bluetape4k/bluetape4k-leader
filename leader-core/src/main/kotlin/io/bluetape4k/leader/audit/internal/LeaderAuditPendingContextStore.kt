package io.bluetape4k.leader.audit.internal

import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * acquisition과 terminal history 사이의 bounded context를 보관합니다.
 *
 * backend token은 digest 계산에만 사용하고 entry에 저장하지 않습니다. 따라서 sink가
 * 오래 살아 있어도 pending map이 token credential을 보유하지 않습니다. capacity와 TTL은
 * 모두 유한하며, eviction은 export를 막지 않는 best-effort 정책입니다.
 */
internal class LeaderAuditPendingContextStore(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val ttl: Duration = DEFAULT_TTL,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val lock = ReentrantLock()
    private val entries = LinkedHashMap<String, Entry>(maxEntries)

    init {
        require(maxEntries in 1..MAX_ENTRIES) {
            "maxEntries must be in 1..$MAX_ENTRIES: $maxEntries"
        }
        require(!ttl.isZero && !ttl.isNegative) { "ttl must be positive: $ttl" }
    }

    /** acquisition context를 저장하고 오래된 entry를 제거합니다. */
    fun put(key: LeaderHistoryKey, record: LeaderLockHistoryRecord) {
        val fingerprint = fingerprint(key)
        val context = PendingContext(
            lockName = record.lockName,
            kind = record.kind,
            acquiredAt = record.acquiredAt,
            lockedUntil = record.lockedUntil,
            nodeId = record.nodeId,
            slotId = record.slotId,
            metadata = record.metadata.toMap(),
        )
        lock.lock()
        try {
            evictExpired(Instant.now(clock))
            entries[fingerprint] = Entry(context, Instant.now(clock))
            while (entries.size > maxEntries) {
                entries.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** terminal history context를 exactly-once로 꺼내 제거합니다. */
    fun remove(key: LeaderHistoryKey): PendingContext? {
        val fingerprint = fingerprint(key)
        lock.lock()
        return try {
            evictExpired(Instant.now(clock))
            entries.remove(fingerprint)?.context
        } finally {
            lock.unlock()
        }
    }

    /** 테스트와 운영 진단을 위한 현재 entry 수입니다. */
    fun size(): Int {
        lock.lock()
        return try {
            evictExpired(Instant.now(clock))
            entries.size
        } finally {
            lock.unlock()
        }
    }

    private fun evictExpired(now: Instant) {
        val cutoff = now.minus(ttl)
        entries.entries.removeIf { it.value.createdAt.isBefore(cutoff) }
    }

    private fun fingerprint(key: LeaderHistoryKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField(key.id?.toString())
        digest.updateField(key.historyId)
        digest.updateField(key.lockName)
        digest.updateField(key.token)
        digest.updateField(key.slotId)
        return digest.digest().joinToString(HEX_SEPARATOR) { byte ->
            "%02x".format(byte.toInt() and BYTE_MASK)
        }
    }

    private fun MessageDigest.updateField(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        val length = bytes?.size ?: NULL_LENGTH
        update(length.toString().toByteArray(Charsets.UTF_8))
        update(FIELD_SEPARATOR)
        if (bytes != null) update(bytes)
        update(FIELD_SEPARATOR)
    }

    internal data class PendingContext(
        val lockName: String,
        val kind: io.bluetape4k.leader.LockIdentity.AnnotationKind,
        val acquiredAt: Instant,
        val lockedUntil: Instant,
        val nodeId: String?,
        val slotId: String?,
        val metadata: Map<String, String>,
    )

    private data class Entry(
        val context: PendingContext,
        val createdAt: Instant,
    )

    private companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 4096
        const val MAX_ENTRIES: Int = 65_536
        val DEFAULT_TTL: Duration = Duration.ofMinutes(15)
        const val NULL_LENGTH: Int = -1
        const val BYTE_MASK: Int = 0xff
        const val HEX_SEPARATOR: String = ""
        val FIELD_SEPARATOR: ByteArray = byteArrayOf(0)
    }
}

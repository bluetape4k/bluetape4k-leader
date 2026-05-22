package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.validateLockName
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

internal class ConsulLeaseHandle(
    val lockName: String,
    val key: String,
    val sessionId: ConsulSessionId,
    val ownerToken: String,
    val auditLeaderId: String,
    val nodeId: String,
    val electedAt: Instant,
    val leaseUntil: Instant,
    val slotId: String? = null,
    val acquiredAtNanos: Long = System.nanoTime(),
) {
    private val released = AtomicBoolean(false)

    val isReleased: Boolean get() = released.get()

    init {
        validateLockName(lockName)
        require(key.isNotBlank()) { "key must not be blank." }
        require(ownerToken.isNotBlank()) { "ownerToken must not be blank." }
        require(auditLeaderId.isNotBlank()) { "auditLeaderId must not be blank." }
        require(nodeId.isNotBlank()) { "nodeId must not be blank." }
        require(slotId == null || slotId.isNotBlank()) { "slotId must be null or not blank." }
        require(!leaseUntil.isBefore(electedAt)) {
            "leaseUntil must not be before electedAt. electedAt=$electedAt, leaseUntil=$leaseUntil"
        }
    }

    fun markReleased(): Boolean = released.compareAndSet(false, true)
}

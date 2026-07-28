package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.validateLockName
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ConsulLeaseHandle`는 Consul backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property lockName Consul backend 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property key Consul backend 계약에서 `key` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property sessionId Consul backend 계약에서 `sessionId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property ownerToken Consul backend 계약에서 `ownerToken` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property auditLeaderId Consul backend 계약에서 `auditLeaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property nodeId Consul backend 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property electedAt Consul backend 계약에서 `electedAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseUntil Consul backend 계약에서 `leaseUntil` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property slotId Consul backend 계약에서 `slotId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property acquiredAtNanos Consul backend 계약에서 `acquiredAtNanos` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
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

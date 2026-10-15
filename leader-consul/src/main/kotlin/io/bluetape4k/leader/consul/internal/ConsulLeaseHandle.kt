package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `ConsulLeaseHandle`는 Consul KV key와 session, owner payload, lease 시각 및 반납 상태를 묶습니다.
 *
 * @property lockName 검증된 최대 255자 lock 이름입니다.
 * @property key Consul KV에서 획득한 lock key입니다.
 * @property sessionId ownership을 보유하고 lease를 갱신할 Consul session ID입니다.
 * @property ownerToken KV owner payload에 저장해 동일 획득자의 상태를 식별하는 token입니다. 외부 로그에 노출하지 않습니다.
 * @property auditLeaderId audit 결과에 노출할 leader ID입니다.
 * @property nodeId payload에 기록하는 현재 노드 식별자입니다.
 * @property electedAt Consul ownership을 획득한 시각입니다.
 * @property leaseUntil payload에 기록한 lease 예정 만료 시각입니다. [electedAt]보다 빠를 수 없습니다.
 * @property slotId 단일 선거에서는 null이고 그룹 선거에서는 슬롯 문자열입니다.
 * @property acquiredAtNanos monotonic nanoTime 기반 획득 시각으로 최소 lease 남은 시간을 계산합니다.
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
        lockName.validateLockName()
        key.requireNotBlank("key")
        ownerToken.requireNotBlank("ownerToken")
        auditLeaderId.requireNotBlank("auditLeaderId")
        nodeId.requireNotBlank("nodeId")
        require(slotId == null || slotId.isNotBlank()) { "slotId must be null or not blank." }
        require(!leaseUntil.isBefore(electedAt)) {
            "leaseUntil must not be before electedAt. electedAt=$electedAt, leaseUntil=$leaseUntil"
        }
    }

    fun markReleased(): Boolean = released.compareAndSet(false, true)
}

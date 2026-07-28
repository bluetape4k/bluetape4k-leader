package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.leader.validateLockName
import io.etcd.jetcd.ByteSequence
import java.util.concurrent.atomic.AtomicBoolean

private val TOKEN_HEX_DIGITS = CharArray(16) { it.toString(16).single() }

/**
 * `EtcdLeaseHandle`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property leaseId etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property lockName etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property ownershipKey etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property acquiredAtNanos etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property slotId etcd backend 호출과 상태 계산에 사용하는 속성입니다.
 */
internal class EtcdLeaseHandle(
    val leaseId: Long,
    val lockName: String,
    val ownershipKey: ByteSequence,
    val acquiredAtNanos: Long = System.nanoTime(),
    val slotId: String? = null,
) {
    private val released = AtomicBoolean(false)

    val token: String = ownershipToken(ownershipKey)
    val isReleased: Boolean get() = released.get()

    init {
        require(leaseId > 0L) { "leaseId must be positive. leaseId=$leaseId" }
        validateLockName(lockName)
        require(!ownershipKey.isEmpty) { "ownershipKey must not be empty." }
    }

    fun markReleased(): Boolean = released.compareAndSet(false, true)

    companion object {
        fun ownershipToken(ownershipKey: ByteSequence): String {
            val bytes = ownershipKey.bytes
            val result = StringBuilder(bytes.size * 2)

            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xFF
                result.append(TOKEN_HEX_DIGITS[unsigned ushr 4])
                result.append(TOKEN_HEX_DIGITS[unsigned and 0x0F])
            }

            return result.toString()
        }
    }
}

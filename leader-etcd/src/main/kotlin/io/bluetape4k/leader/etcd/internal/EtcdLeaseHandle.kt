package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.etcd.jetcd.ByteSequence
import java.util.concurrent.atomic.AtomicBoolean

private val TOKEN_HEX_DIGITS = CharArray(16) { it.toString(16).single() }

/**
 * `EtcdLeaseHandle`는 획득한 etcd lease ID, ownership key, lock 식별자와 반납 상태를 묶습니다.
 *
 * @property leaseId etcd에서 발급한 양수 lease ID이며 unlock/revoke/keep-alive의 기준입니다.
 * @property lockName 검증된 최대 255자 lock 키입니다.
 * @property ownershipKey etcd lock이 반환한 비어 있지 않은 소유 키입니다. unlock에 그대로 사용합니다.
 * @property acquiredAtNanos 획득 시점의 monotonic nanoTime으로 최소 lease 남은 시간을 계산합니다.
 * @property slotId 단일 선거에서는 null이고 그룹 선거에서는 슬롯 번호 문자열입니다.
 */
internal class EtcdLeaseHandle(
    val leaseId: Long,
    val lockName: String,
    val ownershipKey: ByteSequence,
    val acquiredAtNanos: Long = System.nanoTime(),
    val slotId: String? = null,
) {
    companion object: KLogging() {
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

    private val released = AtomicBoolean(false)

    val token: String = ownershipToken(ownershipKey)
    val isReleased: Boolean get() = released.get()

    init {
        leaseId.requirePositiveNumber("leaseId")
        lockName.validateLockName()
        require(!ownershipKey.isEmpty) { "ownershipKey must not be empty." }
    }

    fun markReleased(): Boolean = released.compareAndSet(false, true)

    override fun toString(): String =
        ToStringBuilder(this)
            .add("leaseId", leaseId)
            .add("lockName", lockName)
            .add("ownershipKey", ownershipKey)
            .add("acquiredAtNanos", acquiredAtNanos)
            .add("slotId", slotId)
            .toString()
}

package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.leader.validateLockName
import io.etcd.jetcd.ByteSequence
import java.util.concurrent.atomic.AtomicBoolean

private val TOKEN_HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * Internal owner handle for an etcd lease-bound lock acquisition.
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

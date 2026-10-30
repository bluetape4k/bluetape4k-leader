package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requireStartsWith

/**
 * `EtcdLeaderPaths`는 prefix 아래 단일 lock과 zero-based group slot의 etcd key 경로를 생성합니다.
 *
 */
internal class EtcdLeaderPaths(
    keyPrefix: String = DefaultPrefix,
) {
    val keyPrefix: String = normalizePrefix(keyPrefix)

    fun single(lockName: String): String {
        lockName.validateLockName()
        return "$keyPrefix/single/${EtcdKeyEncoder.encodeSegment(lockName)}"
    }

    fun groupSlot(lockName: String, zeroBasedSlot: Int): String {
        lockName.validateLockName()
        zeroBasedSlot.requireGe(0, "zeroBasedSlot")
        return "$keyPrefix/group/${EtcdKeyEncoder.encodeSegment(lockName)}/slot-$zeroBasedSlot"
    }

    override fun toString(): String = "EtcdLeaderPaths(keyPrefix='$keyPrefix')"

    companion object: KLogging() {
        const val DefaultPrefix: String = "/bluetape4k/leader"

        private fun normalizePrefix(keyPrefix: String): String {
            keyPrefix.requireNotBlank("keyPrefix")
            keyPrefix.requireStartsWith("/") { "keyPrefix must start with '/'. keyPrefix=$keyPrefix" }

            val normalized = keyPrefix.trimEnd('/')
            normalized.requireNotEmpty { "keyPrefix must include a path segment. keyPrefix=$keyPrefix" }

            return normalized
        }
    }
}

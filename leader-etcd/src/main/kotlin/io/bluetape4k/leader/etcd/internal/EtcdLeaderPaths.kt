package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank

/**
 * Builds stable etcd key paths for single and group leader ownership.
 */
internal class EtcdLeaderPaths(
    keyPrefix: String = DefaultPrefix,
) {

    val keyPrefix: String = normalizePrefix(keyPrefix)

    fun single(lockName: String): String {
        validateLockName(lockName)
        return "$keyPrefix/single/${EtcdKeyEncoder.encodeSegment(lockName)}"
    }

    fun groupSlot(lockName: String, zeroBasedSlot: Int): String {
        validateLockName(lockName)
        zeroBasedSlot.requireGe(0, "zeroBasedSlot")
        return "$keyPrefix/group/${EtcdKeyEncoder.encodeSegment(lockName)}/slot-$zeroBasedSlot"
    }

    companion object {
        const val DefaultPrefix: String = "/bluetape4k/leader"

        private fun normalizePrefix(keyPrefix: String): String {
            keyPrefix.requireNotBlank("keyPrefix")
            require(keyPrefix.startsWith('/')) { "keyPrefix must start with '/'. keyPrefix=$keyPrefix" }

            val normalized = keyPrefix.trimEnd('/')
            require(normalized.isNotEmpty()) { "keyPrefix must include a path segment. keyPrefix=$keyPrefix" }
            return normalized
        }
    }
}

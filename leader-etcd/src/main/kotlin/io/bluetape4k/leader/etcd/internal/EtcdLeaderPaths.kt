package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank

/**
 * `EtcdLeaderPaths`는 etcd backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
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

    override fun toString(): String =
        ToStringBuilder(this)
            .add("keyPrefix", keyPrefix)
            .toString()

    companion object: KLogging() {
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

package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.validateLockName

/**
 * `ConsulLeaderPaths`는 Consul backend의 lease, ownership 확인, session/TTL 정리를 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal class ConsulLeaderPaths(
    keyPrefix: String = DefaultPrefix,
) {

    val keyPrefix: String = normalizePrefix(keyPrefix)

    fun single(lockName: String): String {
        validateLockName(lockName)
        return "$keyPrefix/single/${ConsulKeyEncoder.encodeSegment(lockName)}"
    }

    fun group(lockName: String, slot: Int): String {
        validateLockName(lockName)
        require(slot >= 0) { "slot must be non-negative. slot=$slot" }
        return "$keyPrefix/group/${ConsulKeyEncoder.encodeSegment(lockName)}/slot-$slot"
    }

    companion object {
        const val DefaultPrefix: String = "bluetape4k/leader"

        fun validatePrefix(keyPrefix: String) {
            require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank." }
            require(!keyPrefix.startsWith('/')) { "keyPrefix must not start with '/'. keyPrefix=$keyPrefix" }
            require(keyPrefix.trim('/').isNotEmpty()) { "keyPrefix must include a path segment. keyPrefix=$keyPrefix" }
            require(keyPrefix.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == '/' || it == ':' }) {
                "keyPrefix contains invalid characters. Allowed: [a-zA-Z0-9_\\-./:], got: $keyPrefix"
            }
        }

        private fun normalizePrefix(keyPrefix: String): String {
            validatePrefix(keyPrefix)

            val normalized = keyPrefix.trim('/')
            return normalized
        }
    }
}

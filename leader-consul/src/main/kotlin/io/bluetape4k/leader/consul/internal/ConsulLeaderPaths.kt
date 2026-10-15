package io.bluetape4k.leader.consul.internal

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireGe
import io.bluetape4k.support.requireNotBlank

/**
 * `ConsulLeaderPaths`는 prefix 아래 단일 lock key와 group slot key의 Consul KV 경로를 생성합니다.
 *
 */
internal class ConsulLeaderPaths(
    keyPrefix: String = DefaultPrefix,
) {

    val keyPrefix: String = normalizePrefix(keyPrefix)

    fun single(lockName: String): String {
        lockName.validateLockName()
        return "$keyPrefix/single/${ConsulKeyEncoder.encodeSegment(lockName)}"
    }

    fun group(lockName: String, slot: Int): String {
        lockName.validateLockName()
        slot.requireGe(0) { "slot must be non-negative. slot=$slot" }
        return "$keyPrefix/group/${ConsulKeyEncoder.encodeSegment(lockName)}/slot-$slot"
    }

    override fun toString(): String = "ConsulLeaderPaths(keyPrefix='$keyPrefix')"

    companion object {
        const val DefaultPrefix: String = "bluetape4k/leader"

        fun validatePrefix(keyPrefix: String) {
            keyPrefix.requireNotBlank("keyPrefix")
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

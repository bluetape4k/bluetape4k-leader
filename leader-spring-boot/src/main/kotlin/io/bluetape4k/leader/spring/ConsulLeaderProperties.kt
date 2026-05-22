package io.bluetape4k.leader.spring

import java.time.Duration

/**
 * Consul backend properties.
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     consul:
 *       key-prefix: apps/orders/leader
 *       session-name-prefix: orders-leader
 *       lock-delay: 0s
 * ```
 *
 * @property keyPrefix Consul KV key prefix for lock keys. It must not start with `/`.
 * @property sessionNamePrefix prefix used for Consul session names.
 * @property lockDelay Consul session lock delay. Defaults to zero for scheduler-style skip/reacquire behavior.
 */
data class ConsulLeaderProperties(
    val keyPrefix: String = DefaultKeyPrefix,
    val sessionNamePrefix: String = DefaultSessionNamePrefix,
    val lockDelay: Duration = Duration.ZERO,
) {
    companion object {
        const val DefaultKeyPrefix: String = "bluetape4k/leader"
        const val DefaultSessionNamePrefix: String = "bluetape4k-leader"
    }
}

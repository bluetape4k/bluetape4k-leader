package io.bluetape4k.leader.spring

/**
 * etcd backend properties.
 *
 * ```yaml
 * bluetape4k:
 *   leader:
 *     etcd:
 *       key-prefix: /apps/orders/leader
 * ```
 *
 * @property keyPrefix absolute etcd key prefix for lock keys.
 */
data class EtcdLeaderProperties(
    val keyPrefix: String = DefaultKeyPrefix,
) {
    companion object {
        const val DefaultKeyPrefix: String = "/bluetape4k/leader"
    }
}

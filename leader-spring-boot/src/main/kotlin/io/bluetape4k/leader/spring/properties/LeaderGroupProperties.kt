package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderGroupElectionOptions
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Auto-configuration properties for multi-leader group election.
 *
 * Nested under [LeaderElectionProperties.group].
 *
 * ```yaml
 * leader:
 *   group:
 *     max-leaders: 3
 *     wait-time: 5s
 *     lease-time: 60s
 * ```
 *
 * @property maxLeaders Maximum number of concurrent leaders allowed. Default 2
 * @property waitTime Maximum time to wait for a slot acquisition. Default 5 seconds
 * @property leaseTime Maximum time to hold (lease) a slot. Default 60 seconds
 */
data class LeaderGroupProperties(
    val maxLeaders: Int = DefaultMaxLeaders,
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
) {
    companion object {
        const val DefaultMaxLeaders: Int = 2
        val DefaultWaitTime: Duration = Duration.ofSeconds(5)
        val DefaultLeaseTime: Duration = Duration.ofSeconds(60)
    }

    fun toOptions(): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(
            maxLeaders = maxLeaders,
            waitTime = waitTime.toKotlinDuration(),
            leaseTime = leaseTime.toKotlinDuration(),
        )
}

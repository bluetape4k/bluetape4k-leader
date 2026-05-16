package io.bluetape4k.leader.spring.properties

import io.bluetape4k.leader.LeaderElectionOptions
import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Auto-configuration properties for leader election.
 *
 * ```yaml
 * leader:
 *   wait-time: 5s
 *   lease-time: 60s
 *   group:
 *     max-leaders: 3
 *     wait-time: 5s
 *     lease-time: 60s
 * ```
 *
 * @property waitTime Maximum time to wait for leader acquisition. Default 5 seconds
 * @property leaseTime Maximum time to hold (lease) the leader role. Default 60 seconds
 * @property group Properties for multi-leader group election
 */
data class LeaderElectionProperties(
    val waitTime: Duration = DefaultWaitTime,
    val leaseTime: Duration = DefaultLeaseTime,
    val group: LeaderGroupProperties = LeaderGroupProperties(),
) {
    companion object {
        val DefaultWaitTime: Duration = Duration.ofSeconds(5)
        val DefaultLeaseTime: Duration = Duration.ofSeconds(60)
    }

    fun toOptions(): LeaderElectionOptions =
        LeaderElectionOptions(
            waitTime = waitTime.toKotlinDuration(),
            leaseTime = leaseTime.toKotlinDuration(),
        )
}

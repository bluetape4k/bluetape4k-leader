package io.bluetape4k.leader.spring.adapter

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.spring.LeaderProperties
import kotlin.time.toKotlinDuration

/**
 * Adapter that converts [LeaderProperties] to backend-specific Options.
 *
 * Each backend (Mongo, Exposed JDBC/R2DBC) has its own options class, but v1.0 exposes only common properties;
 * backend-specific options (`retryDelay`, `retryStrategy`, `lockOwner`) use their defaults.
 *
 * Exposing backend-specific options is deferred to a follow-up issue.
 */
internal object PropertiesAdapter {

    /** Converts to common [LeaderElectionOptions]. */
    fun toCommonElection(props: LeaderProperties): LeaderElectionOptions =
        LeaderElectionOptions(
            waitTime = props.waitTime.toKotlinDuration(),
            leaseTime = props.leaseTime.toKotlinDuration(),
        )

    /** Converts to common [LeaderGroupElectionOptions]. */
    fun toCommonGroup(props: LeaderProperties): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(
            maxLeaders = props.group.maxLeaders,
            waitTime = props.group.waitTime.toKotlinDuration(),
            leaseTime = props.group.leaseTime.toKotlinDuration(),
        )
}

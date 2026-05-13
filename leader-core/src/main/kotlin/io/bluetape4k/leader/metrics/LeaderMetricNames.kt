package io.bluetape4k.leader.metrics

/**
 * Metric name constants for the leader AOP layer.
 *
 * ## Contract
 * All constants are stable public API — do not rename without a deprecation cycle.
 *
 * ## Usage
 * ```kotlin
 * registry.counter(LeaderMetricNames.METRIC_LEADER_ID_RESOLUTION_FAILED)
 *     .increment()
 * ```
 */
object LeaderMetricNames {

    /**
     * Counter incremented when the leader ID resolution chain exhausts all fallback levels
     * and throws [io.bluetape4k.leader.identity.LeaderIdResolutionException].
     *
     * Incremented from the PR7 aspect on every [LeaderIdResolutionException].
     */
    const val METRIC_LEADER_ID_RESOLUTION_FAILED: String = "leader.aop.leader_id.resolution_failed"
}

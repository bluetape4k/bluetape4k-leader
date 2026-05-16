package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector

/**
 * Configuration class for [LeaderElectionPlugin].
 *
 * ## Behavior / Contract
 * - [leaderElection] is required and must be configured inside the `install(LeaderElectionPlugin) { ... }` block.
 * - [leaderGroupElection] is optional and should only be configured for applications that need multi-leader (group election).
 * - If not configured, an [IllegalArgumentException] is thrown at plugin installation time.
 *
 * ```kotlin
 * fun Application.module() {
 *     install(LeaderElectionPlugin) {
 *         leaderElection = RedissonSuspendLeaderElector(redissonClient)
 *         leaderGroupElection = RedissonSuspendLeaderGroupElector(redissonClient)
 *     }
 * }
 * ```
 *
 * @property leaderElection single-leader elector backend (required)
 * @property leaderGroupElection group/multi-leader elector backend (optional)
 * @property managementRouteEnabled whether to install the management status route
 * @property managementRoutePath path for the management status route
 */
class LeaderElectionPluginConfig {

    companion object {
        const val DefaultManagementRoutePath: String = "/management/leaderElection"
    }

    /** Single-leader elector backend. Required before installing the plugin. */
    var leaderElection: SuspendLeaderElector? = null

    /** Group/multi-leader elector backend. Optional. */
    var leaderGroupElection: SuspendLeaderGroupElector? = null

    /** Whether to install the Ktor management status route. Defaults to false. */
    var managementRouteEnabled: Boolean = false

    /** Management route path. Defaults to `/management/leaderElection`. */
    var managementRoutePath: String = DefaultManagementRoutePath

    internal val managementRegistry: LeaderElectionManagementRegistry = LeaderElectionManagementRegistry()

    /**
     * Registers static lock names exposed through the management route.
     */
    fun managementLockNames(vararg lockNames: String) {
        lockNames.forEach(managementRegistry::register)
    }
}

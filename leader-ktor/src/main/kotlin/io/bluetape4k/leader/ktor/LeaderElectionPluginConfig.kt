package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector

/**
 * [LeaderElectionPlugin] 의 설정 클래스입니다.
 *
 * ## 동작/계약
 * - [leaderElection] 은 필수 항목으로, `install(LeaderElectionPlugin) { ... }` 블록에서 반드시 설정해야 합니다.
 * - [leaderGroupElection] 은 선택 항목으로, 멀티 리더(그룹 선출)가 필요한 애플리케이션에서만 설정합니다.
 * - 미설정 시 플러그인 설치 시점에 [IllegalArgumentException] 이 발생합니다.
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

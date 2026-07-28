package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector

/**
 * `LeaderElectionPluginConfig`는 Ktor integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property DefaultManagementRoutePath Ktor integration 계약에서 사용하는 속성입니다.
 * @property managementRegistry Ktor integration 계약에서 사용하는 속성입니다.
 */
class LeaderElectionPluginConfig {

    companion object {
        const val DefaultManagementRoutePath: String = "/management/leaderElection"
    }

    /**
     * `leaderElection` 값은 Ktor integration 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    var leaderElection: SuspendLeaderElector? = null

    /**
     * `leaderGroupElection` 값은 Ktor integration 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    var leaderGroupElection: SuspendLeaderGroupElector? = null

    /**
     * `managementRouteEnabled` 값은 Ktor integration 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    var managementRouteEnabled: Boolean = false

    /**
     * `managementRoutePath` 값은 Ktor integration 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    var managementRoutePath: String = DefaultManagementRoutePath

    internal val managementRegistry: LeaderElectionManagementRegistry = LeaderElectionManagementRegistry()

    /**
     * `managementLockNames` 호출은 Ktor integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun managementLockNames(vararg lockNames: String) {
        lockNames.forEach(managementRegistry::register)
    }
}

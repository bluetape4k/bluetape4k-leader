package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        const val DefaultBackendDiagnosticsRoutePath: String = "/management/leaderElection/diagnostics"
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

    /** Backend diagnostics route를 설치할지 지정합니다. */
    var backendDiagnosticsRouteEnabled: Boolean = false

    /** Backend diagnostics route 경로입니다. */
    var backendDiagnosticsRoutePath: String = DefaultBackendDiagnosticsRoutePath

    /** Diagnostics 응답을 만들 때 connectivity probe를 실행할지 지정합니다. */
    var backendConnectivityCheckEnabled: Boolean = false

    /** Connectivity probe에 전달할 제한 시간입니다. */
    var backendConnectivityCheckTimeout: Duration = LeaderBackendDiagnosticsProvider.DefaultProbeTimeout

    /** write management action route를 명시적으로 설치할 수 있는지 지정합니다. */
    var managementActionRouteEnabled: Boolean = false

    /** write management action route가 사용할 application-owned registry입니다. */
    var managementActionRegistry: SuspendLeaderManagementActionRegistry? = null

    /** write management action route의 canonical base path입니다. */
    var managementActionRoutePath: String? = null

    /** leader event stream route를 설치할지 지정합니다. 기본값은 비활성화입니다. */
    var eventStreamRouteEnabled: Boolean = false

    /** leader event stream의 SSE canonical path입니다. */
    var eventStreamRoutePath: String = "/management/leaderElection/events"

    /** leader event stream의 SSE transport를 활성화할지 지정합니다. */
    var eventStreamSseEnabled: Boolean = true

    /** leader event stream의 WebSocket transport를 활성화할지 지정합니다. */
    var eventStreamWebSocketEnabled: Boolean = false

    /** lock filter 없이 모든 lock event를 받을지 지정합니다. */
    var eventStreamAllLocksEnabled: Boolean = false

    /** event payload에 lockName을 포함할지 지정합니다. 기본값은 비노출입니다. */
    var eventStreamExposeLockName: Boolean = false

    /** event payload에 leaderId와 leaseExpiry를 포함할지 지정합니다. 기본값은 비노출입니다. */
    var eventStreamExposeLeaderMetadata: Boolean = false

    /** replay ring buffer의 최대 보존 event 수입니다. 0은 live-only입니다. */
    var eventStreamReplayCapacity: Int = 32

    /** event stream의 동시 connection 상한입니다. */
    var eventStreamMaxConnections: Int = 128

    /** connection heartbeat 주기입니다. */
    var eventStreamHeartbeat: Duration = 15.seconds

    /** 오류 응답을 typed override로 제한하는 optional policy입니다. */
    internal var errorResponder: LeaderElectionErrorResponder? = null

    /** 오류 code별 status와 lockName 노출을 제한하는 optional policy입니다. */
    internal var errorOverrides: Map<LeaderElectionErrorCode, LeaderElectionErrorOverride> = emptyMap()

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

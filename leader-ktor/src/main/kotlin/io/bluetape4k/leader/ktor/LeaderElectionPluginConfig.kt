package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `LeaderElectionPlugin`이 사용할 elector, 관리 route, diagnostics route, event stream 설정을 보관합니다.
 *
 * @property DefaultManagementRoutePath management route의 기본 경로인 `/management/leaderElection`입니다.
 * @property managementRegistry management route 응답에 포함할 lock 이름을 정렬된 집합으로 보관하는 내부 registry입니다.
 */
class LeaderElectionPluginConfig {

    companion object {
        const val DefaultManagementRoutePath: String = "/management/leaderElection"
        const val DefaultBackendDiagnosticsRoutePath: String = "/management/leaderElection/diagnostics"
    }

    /** plugin이 management route와 scheduled 작업에 사용할 suspend elector입니다. 설치 시 반드시 설정해야 합니다. */
    var leaderElection: SuspendLeaderElector? = null

    /** group election API가 필요한 management action에서 사용할 suspend group elector입니다. */
    var leaderGroupElection: SuspendLeaderGroupElector? = null

    /** `true`이면 등록한 lock의 상태를 JSON으로 반환하는 read-only management route를 설치합니다. 기본값은 `false`입니다. */
    var managementRouteEnabled: Boolean = false

    /** management route의 application path입니다. 기본값은 `/management/leaderElection`입니다. */
    var managementRoutePath: String = DefaultManagementRoutePath

    /** `true`이면 backend diagnostics route를 설치합니다. 기본값은 `false`이며 elector가 diagnostics provider를 제공해야 합니다. */
    var backendDiagnosticsRouteEnabled: Boolean = false

    /** backend diagnostics route의 application path입니다. 기본값은 `/management/leaderElection/diagnostics`입니다. */
    var backendDiagnosticsRoutePath: String = DefaultBackendDiagnosticsRoutePath

    /** `true`이면 diagnostics 응답을 만들 때 backend connectivity probe를 실행합니다. 기본값은 `false`입니다. */
    var backendConnectivityCheckEnabled: Boolean = false

    /** connectivity probe의 timeout입니다. 기본값은 `LeaderBackendDiagnosticsProvider.DefaultProbeTimeout`입니다. */
    var backendConnectivityCheckTimeout: Duration = LeaderBackendDiagnosticsProvider.DefaultProbeTimeout

    /** `true`이면 write management action route를 설치합니다. 기본값은 `false`이며 application-owned registry가 필요합니다. */
    var managementActionRouteEnabled: Boolean = false

    /** write action을 등록·실행할 application-owned registry입니다. action route를 켜면 `null`일 수 없습니다. */
    var managementActionRegistry: SuspendLeaderManagementActionRegistry? = null

    /** write management action route의 선택적 canonical base path입니다. `null`이면 management path 뒤에 `/actions`를 붙입니다. */
    var managementActionRoutePath: String? = null

    /** `true`이면 leader event stream route를 설치합니다. 기본값은 비활성화이며 caller의 route 등록도 필요합니다. */
    var eventStreamRouteEnabled: Boolean = false

    /** leader event stream이 사용할 canonical path입니다. 기본값은 `/management/leaderElection/events`입니다. */
    var eventStreamRoutePath: String = "/management/leaderElection/events"

    /** event stream의 SSE transport를 활성화합니다. 기본값은 `true`입니다. */
    var eventStreamSseEnabled: Boolean = true

    /** event stream의 WebSocket transport를 활성화합니다. 기본값은 `false`입니다. */
    var eventStreamWebSocketEnabled: Boolean = false

    /** `true`이면 특정 lock filter 없이 모든 lock event를 받습니다. 이때 lock 이름 노출도 켜야 합니다. */
    var eventStreamAllLocksEnabled: Boolean = false

    /** event payload에 lock 이름을 포함할지 정합니다. 기본값은 비노출(`false`)입니다. */
    var eventStreamExposeLockName: Boolean = false

    /** event payload에 leader ID와 lease 만료 시각을 포함할지 정합니다. 기본값은 비노출(`false`)입니다. */
    var eventStreamExposeLeaderMetadata: Boolean = false

    /** replay ring buffer에 보관할 최대 event 수입니다. `0..1024` 범위이며 `0`은 live-only입니다. 기본값은 `32`입니다. */
    var eventStreamReplayCapacity: Int = 32

    /** event stream의 동시 connection 상한입니다. `1..1024` 범위이며 기본값은 `128`입니다. */
    var eventStreamMaxConnections: Int = 128

    /** 연결 heartbeat 주기입니다. 유한한 양수여야 하며 기본값은 15초입니다. */
    var eventStreamHeartbeat: Duration = 15.seconds

    /** 오류 응답을 typed override로 제한하는 optional policy입니다. */
    internal var errorResponder: LeaderElectionErrorResponder? = null

    /** 오류 code별 status와 lockName 노출을 제한하는 optional policy입니다. */
    internal var errorOverrides: Map<LeaderElectionErrorCode, LeaderElectionErrorOverride> = emptyMap()

    internal val managementRegistry: LeaderElectionManagementRegistry = LeaderElectionManagementRegistry()

    /** management route의 상태 응답 대상 lock 이름을 등록합니다. 각 이름은 `validateLockName` 규칙으로 검증됩니다. */
    fun managementLockNames(vararg lockNames: String) {
        lockNames.forEach(managementRegistry::register)
    }
}

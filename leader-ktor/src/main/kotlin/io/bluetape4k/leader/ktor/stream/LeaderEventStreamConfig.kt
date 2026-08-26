package io.bluetape4k.leader.ktor.stream

import io.bluetape4k.leader.ktor.LeaderElectionPluginConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Ktor leader event stream의 불변 실행 설정입니다.
 *
 * route와 transport는 기본적으로 비활성화되어 있으며, replay와 connection 수는
 * 항상 bounded 범위 안에서만 사용할 수 있습니다. leader identity와 lock 이름은
 * 명시적인 opt-in 없이는 event payload에 포함하지 않습니다.
 */
internal data class LeaderEventStreamConfig(
    val eventStreamRouteEnabled: Boolean = false,
    val eventStreamRoutePath: String = "/management/leaderElection/events",
    val eventStreamSseEnabled: Boolean = true,
    val eventStreamWebSocketEnabled: Boolean = false,
    val eventStreamAllLocksEnabled: Boolean = false,
    val eventStreamExposeLockName: Boolean = false,
    val eventStreamExposeLeaderMetadata: Boolean = false,
    val eventStreamReplayCapacity: Int = 32,
    val eventStreamMaxConnections: Int = 128,
    val eventStreamHeartbeat: Duration = 15.seconds,
) {

    init {
        validate()
    }

    /** plugin startup에서 동일한 bounded 정책을 다시 확인할 수 있습니다. */
    internal fun validate() {
        require(eventStreamRoutePath.isNotBlank() && eventStreamRoutePath.startsWith('/')) {
            "eventStreamRoutePath는 비어 있지 않은 absolute path여야 합니다: $eventStreamRoutePath"
        }
        require(eventStreamReplayCapacity in REPLAY_CAPACITY_RANGE) {
            "eventStreamReplayCapacity는 ${REPLAY_CAPACITY_RANGE.first}..${REPLAY_CAPACITY_RANGE.last} 범위여야 합니다: " +
                eventStreamReplayCapacity
        }
        require(eventStreamMaxConnections in MAX_CONNECTIONS_RANGE) {
            "eventStreamMaxConnections는 ${MAX_CONNECTIONS_RANGE.first}..${MAX_CONNECTIONS_RANGE.last} 범위여야 합니다: " +
                eventStreamMaxConnections
        }
        require(eventStreamHeartbeat.isFinite() && eventStreamHeartbeat.isPositive()) {
            "eventStreamHeartbeat는 유한한 양수여야 합니다: $eventStreamHeartbeat"
        }
        if (eventStreamRouteEnabled) {
            require(eventStreamSseEnabled || eventStreamWebSocketEnabled) {
                "eventStreamRouteEnabled=true이면 SSE 또는 WebSocket을 하나 이상 활성화해야 합니다."
            }
        }
        if (eventStreamAllLocksEnabled) {
            require(eventStreamExposeLockName) {
                "eventStreamAllLocksEnabled=true이면 eventStreamExposeLockName=true가 필요합니다."
            }
        }
    }

    private companion object {
        val REPLAY_CAPACITY_RANGE: IntRange = 0..1024
        val MAX_CONNECTIONS_RANGE: IntRange = 1..1024
    }
}

/**
 * application plugin DSL 설정을 event stream이 소비하는 불변 설정으로 복사합니다.
 */
internal fun LeaderElectionPluginConfig.toLeaderEventStreamConfig(): LeaderEventStreamConfig =
    LeaderEventStreamConfig(
        eventStreamRouteEnabled = eventStreamRouteEnabled,
        eventStreamRoutePath = eventStreamRoutePath,
        eventStreamSseEnabled = eventStreamSseEnabled,
        eventStreamWebSocketEnabled = eventStreamWebSocketEnabled,
        eventStreamAllLocksEnabled = eventStreamAllLocksEnabled,
        eventStreamExposeLockName = eventStreamExposeLockName,
        eventStreamExposeLeaderMetadata = eventStreamExposeLeaderMetadata,
        eventStreamReplayCapacity = eventStreamReplayCapacity,
        eventStreamMaxConnections = eventStreamMaxConnections,
        eventStreamHeartbeat = eventStreamHeartbeat,
    )

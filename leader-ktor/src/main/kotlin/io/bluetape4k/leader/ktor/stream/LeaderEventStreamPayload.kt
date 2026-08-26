package io.bluetape4k.leader.ktor.stream

import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.ktor.jsonValue

/**
 * Leader event stream에서 사용하는 stable JSON payload builder입니다.
 *
 * core의 [LeaderElectionEvent]를 그대로 직렬화하지 않고 허용된 event type과 sequence,
 * 명시적으로 허용한 metadata만 기록합니다. 따라서 [LeaderElectionEvent.Elected.leader]
 * 안의 전체 [io.bluetape4k.leader.LeaderLease]나 backend 정보는 payload에 들어가지 않습니다.
 */
internal object LeaderEventStreamPayload {

    /** core event를 safe JSON으로 변환합니다. */
    fun event(
        event: LeaderElectionEvent,
        sequence: Long,
        exposeLockName: Boolean = false,
        exposeLeaderMetadata: Boolean = false,
    ): String {
        require(sequence >= 0) { "event sequence는 음수가 될 수 없습니다: $sequence" }

        val type = when (event) {
            is LeaderElectionEvent.Elected -> "Elected"
            is LeaderElectionEvent.Revoked -> "Revoked"
            is LeaderElectionEvent.Skipped -> "Skipped"
        }

        return buildString {
            append("{\"type\":").append(type.jsonValue())
            append(",\"sequence\":").append(sequence)
            if (exposeLockName) {
                append(",\"lockName\":").append(event.lockName.jsonValue())
            }
            if (exposeLeaderMetadata) {
                val elected = event as? LeaderElectionEvent.Elected
                append(",\"leaderId\":")
                    .append(elected?.leaderId?.jsonValue() ?: "null")
                append(",\"leaseExpiry\":")
                    .append(elected?.leaseExpiry?.toString()?.jsonValue() ?: "null")
            }
            append('}')
        }
    }

    /** immutable stream config에 정의된 exposure 정책으로 core event를 변환합니다. */
    fun event(
        event: LeaderElectionEvent,
        sequence: Long,
        config: LeaderEventStreamConfig,
    ): String = event(
        event = event,
        sequence = sequence,
        exposeLockName = config.eventStreamExposeLockName,
        exposeLeaderMetadata = config.eventStreamExposeLeaderMetadata,
    )

    /** 연결 유지용 heartbeat control payload입니다. */
    fun heartbeat(): String = buildString {
        append("{\"event\":\"heartbeat\"}")
    }

    /** replay cursor가 보존 범위를 벗어났음을 알리는 control payload입니다. */
    fun replayGap(from: Long, to: Long): String {
        require(from >= 0) { "replay gap의 from은 음수가 될 수 없습니다: $from" }
        require(to >= 0) { "replay gap의 to는 음수가 될 수 없습니다: $to" }
        require(from <= to) { "replay gap의 from은 to보다 클 수 없습니다: from=$from, to=$to" }
        return "{\"event\":\"replay_gap\",\"from\":$from,\"to\":$to}"
    }
}

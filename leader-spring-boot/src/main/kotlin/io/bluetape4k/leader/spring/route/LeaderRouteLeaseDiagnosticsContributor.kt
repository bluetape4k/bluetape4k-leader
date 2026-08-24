package io.bluetape4k.leader.spring.route

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation

/**
 * 요청별 lease runtime의 core-only aggregate diagnostics를 노출합니다.
 *
 * 동적 lock 이름, leader identity, backend 주소, 예외 세부정보는 이 모델에 존재하지
 * 않습니다. endpoint wiring은 Actuator가 있는 Spring context에서만 수행됩니다.
 */
@Endpoint(id = "leaderRouteLease")
internal class LeaderRouteLeaseDiagnosticsContributor(
    private val runtime: LeaderRouteLeaseRuntime,
) {

    @ReadOperation
    fun diagnostics(): LeaderRouteLeaseDiagnostics = runtime.diagnostics()
}

/** 고정 schema의 route lease aggregate입니다. */
data class LeaderRouteLeaseDiagnostics(
    val runtimeState: String,
    val active: Int,
    val effectiveActiveCapacity: Int,
    val acquireInFlight: Int,
    val acquireQueueAvailable: Int,
    val cleanupInFlight: Int,
    val cleanupQueueAvailable: Int,
    val watchdogInFlight: Int,
    val residual: Int,
    val observations: Map<String, Long>,
)

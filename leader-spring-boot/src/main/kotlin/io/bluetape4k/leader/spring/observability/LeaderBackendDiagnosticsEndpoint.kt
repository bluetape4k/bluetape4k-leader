package io.bluetape4k.leader.spring.observability

import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnostics
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation

/** 외부 I/O 없이 선택된 leader backend의 정적 diagnostics를 노출합니다. */
@Endpoint(id = "leaderBackendDiagnostics")
class LeaderBackendDiagnosticsEndpoint(
    private val provider: LeaderBackendDiagnosticsProvider,
) {

    /** Backend descriptor와 `NOT_CHECKED` connectivity를 반환합니다. */
    @ReadOperation
    fun leaderBackendDiagnostics(): LeaderBackendDiagnostics = provider.diagnostics()
}

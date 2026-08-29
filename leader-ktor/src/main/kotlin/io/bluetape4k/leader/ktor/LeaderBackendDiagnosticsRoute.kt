package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnostics
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * Leader backend의 정적 capability와 선택적인 connectivity 결과를 JSON route로 노출합니다.
 *
 * [connectivityCheckEnabled]가 `false`이면 외부 backend I/O를 실행하지 않습니다.
 * connectivity payload에는 상태와 함께 bounded [LeaderBackendConnectivity.reason]을
 * 포함하며, provider 예외의 HTTP status 처리는 application pipeline이 소유합니다.
 */
fun Application.leaderBackendDiagnosticsRoute(
    path: String = LeaderElectionPluginConfig.DefaultBackendDiagnosticsRoutePath,
    provider: LeaderBackendDiagnosticsProvider,
    connectivityCheckEnabled: Boolean = false,
    connectivityCheckTimeout: Duration = LeaderBackendDiagnosticsProvider.DefaultProbeTimeout,
) {
    val routePath = normalizeLeaderRoutePath(path)
    if (connectivityCheckEnabled) {
        validateBackendConnectivityCheckTimeout(
            timeout = connectivityCheckTimeout,
            propertyName = "connectivityCheckTimeout",
        )
    }

    routing {
        get(routePath) {
            val diagnostics = if (connectivityCheckEnabled) {
                withContext(Dispatchers.IO) {
                    provider.diagnostics(probe = true, timeout = connectivityCheckTimeout)
                }
            } else {
                provider.diagnostics()
            }
            call.respondText(
                text = diagnostics.toJson(),
                contentType = ContentType.Application.Json,
            )
        }
    }
}

/** Connectivity probe timeout의 public route 계약을 검증합니다. */
internal fun validateBackendConnectivityCheckTimeout(timeout: Duration, propertyName: String) {
    require(timeout.isFinite() && timeout.isPositive()) {
        "${propertyName}은 양수이면서 유한해야 합니다: $timeout"
    }
}

private fun LeaderBackendDiagnostics.toJson(): String =
    buildString {
        append("{\"descriptor\":")
        appendDescriptor(descriptor)
        append(",\"connectivity\":")
        appendConnectivity(connectivity)
        append('}')
    }

private fun StringBuilder.appendDescriptor(descriptor: LeaderBackendDescriptor) {
    append("{\"backendId\":").append(descriptor.backendId.jsonValue())
    append(",\"displayName\":").append(descriptor.displayName.jsonValue())
    append(",\"capabilities\":")
    appendCapabilities(descriptor.capabilities)
    append('}')
}

private fun StringBuilder.appendCapabilities(capabilities: LeaderBackendCapabilities) {
    append("{\"singleExecutionModels\":")
    appendExecutionModels(capabilities.singleExecutionModels)
    append(",\"groupExecutionModels\":")
    appendExecutionModels(capabilities.groupExecutionModels)
    append(",\"leaseExtension\":")
    appendModeSupport(capabilities.leaseExtension)
    append(",\"auditState\":")
    appendModeSupport(capabilities.auditState)
    append(",\"clockSource\":").append(capabilities.clockSource.name.jsonValue())
    append(",\"ttlMode\":").append(capabilities.ttlMode.name.jsonValue())
    append(",\"limitations\":[")
    capabilities.limitations.forEachIndexed { index, limitation ->
        if (index > 0) append(',')
        append(limitation.jsonValue())
    }
    append("]}")
}

private fun StringBuilder.appendExecutionModels(models: Set<LeaderExecutionModel>) {
    append('[')
    models.sortedBy(LeaderExecutionModel::ordinal).forEachIndexed { index, model ->
        if (index > 0) append(',')
        append(model.name.jsonValue())
    }
    append(']')
}

private fun StringBuilder.appendModeSupport(modeSupport: LeaderBackendModeSupport) {
    append("{\"single\":").append(modeSupport.single.name.jsonValue())
    append(",\"group\":").append(modeSupport.group.name.jsonValue())
    append('}')
}

private fun StringBuilder.appendConnectivity(connectivity: LeaderBackendConnectivity) {
    append("{\"status\":").append(connectivity.status.name.jsonValue())
    append(",\"checkedAt\":").append(connectivity.checkedAt?.toString()?.jsonValue() ?: "null")
    append(",\"latencyMillis\":").append(connectivity.latencyMillis ?: "null")
    append(",\"reason\":").append(connectivity.reason.name.jsonValue())
    append('}')
}

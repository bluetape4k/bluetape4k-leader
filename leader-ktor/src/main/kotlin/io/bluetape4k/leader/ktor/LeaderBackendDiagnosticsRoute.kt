package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.diagnostics.LeaderBackendCapabilities
import io.bluetape4k.leader.diagnostics.LeaderBackendConnectivity
import io.bluetape4k.leader.diagnostics.LeaderBackendDescriptor
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnostics
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LeaderBackendModeSupport
import io.bluetape4k.leader.diagnostics.LeaderExecutionModel
import io.bluetape4k.support.requireNotBlank
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
 */
fun Application.leaderBackendDiagnosticsRoute(
    path: String = LeaderElectionPluginConfig.DefaultBackendDiagnosticsRoutePath,
    provider: LeaderBackendDiagnosticsProvider,
    connectivityCheckEnabled: Boolean = false,
    connectivityCheckTimeout: Duration = LeaderBackendDiagnosticsProvider.DefaultProbeTimeout,
) {
    path.requireNotBlank("path")
    val routePath = if (path.startsWith("/")) path else "/$path"

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
    append('}')
}

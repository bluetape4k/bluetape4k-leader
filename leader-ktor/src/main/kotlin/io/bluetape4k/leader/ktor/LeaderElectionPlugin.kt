package io.bluetape4k.leader.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey

/**
 * `LeaderElectionPlugin` 값은 Ktor integration 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
val LeaderElectionPlugin = createApplicationPlugin(
    name = LeaderElectionPluginInternals.NAME,
    createConfiguration = ::LeaderElectionPluginConfig,
) {
    val config = pluginConfig
    val leaderElection = requireNotNull(config.leaderElection) {
        "LeaderElectionPlugin 설치 전 leaderElection 을 반드시 설정해야 합니다."
    }

    // 외부 (예: leaderScheduled 확장) 에서 설정에 접근할 수 있도록 Application attributes 에 저장한다.
    application.attributes.put(LeaderElectionConfigKey, config)

    if (config.managementRouteEnabled) {
        application.leaderElectionManagementRoute(
            path = config.managementRoutePath,
            leaderElection = leaderElection,
            registry = config.managementRegistry,
        )
    }

    if (config.backendDiagnosticsRouteEnabled) {
        val diagnosticsProvider = leaderElection.resolveBackendDiagnosticsProvider()
        requireNotNull(diagnosticsProvider) {
            "backendDiagnosticsRouteEnabled=true 이면 leaderElection 이 backend diagnostics provider를 제공해야 합니다."
        }
        if (config.backendConnectivityCheckEnabled) {
            val timeout = config.backendConnectivityCheckTimeout
            require(timeout.isFinite() && timeout.isPositive()) {
                "backendConnectivityCheckTimeout은 양수이면서 유한해야 합니다: " +
                        timeout
            }
        }
        application.leaderBackendDiagnosticsRoute(
            path = config.backendDiagnosticsRoutePath,
            provider = diagnosticsProvider,
            connectivityCheckEnabled = config.backendConnectivityCheckEnabled,
            connectivityCheckTimeout = config.backendConnectivityCheckTimeout,
        )
    }

    on(MonitoringEvent(ApplicationStarted)) { application ->
        LeaderElectionPluginInternals.log.info {
            "LeaderElectionPlugin 시작 — application=${application.javaClass.simpleName}"
        }
    }

    on(MonitoringEvent(ApplicationStopped)) { application ->
        LeaderElectionPluginInternals.log.info {
            "LeaderElectionPlugin 종료 — application=${application.javaClass.simpleName}"
        }
    }
}

private fun Any.resolveBackendDiagnosticsProvider(): LeaderBackendDiagnosticsProvider? =
    when (this) {
        is LeaderBackendDiagnosticsProvider -> this
        is LeaderBackendDiagnosticsAware -> backendDiagnosticsProvider
        else -> null
    }

/**
 * `LeaderElectionConfigKey` 값은 Ktor integration 계약에서 사용하는 설정 또는 상태 항목입니다.
 */
internal val LeaderElectionConfigKey: AttributeKey<LeaderElectionPluginConfig> =
    AttributeKey("io.bluetape4k.leader.ktor.LeaderElectionPluginConfig")

/**
 * `Application` 호출은 Ktor integration 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun Application.leaderElectionPluginConfig(): LeaderElectionPluginConfig {
    return attributes.getOrNull(LeaderElectionConfigKey)
        ?: error("LeaderElectionPlugin 이 Application 에 설치되지 않았습니다.")
}

/**
 * `LeaderElectionPluginInternals`는 Ktor integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
internal object LeaderElectionPluginInternals: KLogging() {
    const val NAME: String = "LeaderElection"
}

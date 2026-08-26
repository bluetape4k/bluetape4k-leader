package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamHub
import io.bluetape4k.leader.ktor.stream.toLeaderEventStreamConfig
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
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
    val resourceRegistry = LeaderElectionResourceRegistryImpl()
    application.attributes.put(LeaderElectionResourceRegistryKey, resourceRegistry)

    val eventStreamConfig = config.toLeaderEventStreamConfig()
    if (eventStreamConfig.eventStreamRouteEnabled) {
        val publisher = leaderElection as? LeaderElectionEventPublisher
            ?: throw LeaderElectionConfigurationException(
                "eventStreamRouteEnabled=true이면 leaderElection이 LeaderElectionEventPublisher여야 합니다.",
            )
        val hub = LeaderEventStreamHub(
            publisher = publisher,
            capacity = eventStreamConfig.eventStreamReplayCapacity,
            scope = application,
            maxConnections = eventStreamConfig.eventStreamMaxConnections,
            allLocksEnabled = eventStreamConfig.eventStreamAllLocksEnabled,
        )
        val runtime = LeaderEventStreamRuntime(hub = hub, config = eventStreamConfig)
        application.install(LeaderEventStreamRuntimePlugin) {
            this.runtime = runtime
        }
        resourceRegistry.register(hub)
    }

    if (config.managementActionRouteEnabled) {
        requireNotNull(config.managementActionRegistry) {
            "managementActionRouteEnabled=true 이면 application-owned managementActionRegistry를 설정해야 합니다."
        }
        config.managementActionPath()
    }

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
        val eventStreamRuntime = application.pluginOrNull(LeaderEventStreamRuntimePlugin)
        if (eventStreamRuntime != null &&
            eventStreamRuntime.config.eventStreamRouteEnabled &&
            !eventStreamRuntime.routeRegistered.get()
        ) {
            throw LeaderElectionConfigurationException(
                "eventStreamRouteEnabled=true이면 caller route에서 leaderElectionEventStream()을 한 번 등록해야 합니다.",
            )
        }
        LeaderElectionPluginInternals.log.info {
            "LeaderElectionPlugin 시작 — application=${application.javaClass.simpleName}"
        }
    }

    on(MonitoringEvent(ApplicationStopped)) { _ ->
        resourceRegistry.observeShutdown { report ->
            LeaderElectionPluginInternals.log.info {
                "LeaderElectionPlugin resource shutdown — " +
                    "attempted=${report.attempted}, closed=${report.closed}, " +
                    "failures=${report.failures}, timedOutJobs=${report.timedOutJobs}, " +
                    "timedOutResources=${report.timedOutResources}, " +
                    "failureKinds=${report.failureKinds}, timeoutKinds=${report.timeoutKinds}"
            }
        }
        resourceRegistry.close()
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
 * Plugin이 소유하는 application resource registry attribute입니다.
 */
internal val LeaderElectionResourceRegistryKey: AttributeKey<LeaderElectionResourceRegistry> =
    AttributeKey("io.bluetape4k.leader.ktor.LeaderElectionResourceRegistry")

/**
 * Application-owned resource registry를 조회합니다. Plugin이 설치되지 않았으면 `null`입니다.
 */
internal fun Application.leaderElectionResourceRegistryOrNull(): LeaderElectionResourceRegistry? =
    attributes.getOrNull(LeaderElectionResourceRegistryKey)

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

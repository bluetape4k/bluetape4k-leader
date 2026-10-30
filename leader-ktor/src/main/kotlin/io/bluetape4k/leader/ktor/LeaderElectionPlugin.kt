package io.bluetape4k.leader.ktor

import io.bluetape4k.ktor.core.installApplicationResourceLifecycle
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.ktor.stream.LeaderEventStreamHub
import io.bluetape4k.leader.ktor.stream.toLeaderEventStreamConfig
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.util.AttributeKey

/**
 * `LeaderElectionPluginConfig`로 elector와 선택한 management/diagnostics/event route를 Application에 설치합니다.
 */
@Suppress("TooGenericExceptionCaught")
val LeaderElectionPlugin = createApplicationPlugin(
    name = LeaderElectionPluginInternals.NAME,
    createConfiguration = ::LeaderElectionPluginConfig,
) {
    val config = pluginConfig
    val leaderElection = requireNotNull(config.leaderElection) {
        "LeaderElectionPlugin 설치 전 leaderElection 을 반드시 설정해야 합니다."
    }
    validateManagementDiagnosticsRoutePathCollision(config)

    // 외부 (예: leaderScheduled 확장) 에서 설정에 접근할 수 있도록 Application attributes 에 저장한다.
    application.attributes.put(LeaderElectionConfigKey, config)
    val resourceRegistry = LeaderElectionResourceRegistryImpl()
    try {
        application.attributes.put(LeaderElectionResourceRegistryKey, resourceRegistry)
        resourceRegistry.observeShutdown { report ->
            LeaderElectionPluginInternals.log.info {
                "LeaderElectionPlugin resource shutdown — " +
                        "attempted=${report.attempted}, closed=${report.closed}, " +
                        "failures=${report.failures}, timedOutJobs=${report.timedOutJobs}, " +
                        "timedOutResources=${report.timedOutResources}, " +
                        "failureKinds=${report.failureKinds}, timeoutKinds=${report.timeoutKinds}"
            }
        }
        application.installApplicationResourceLifecycle().register(resourceRegistry)

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
                validateBackendConnectivityCheckTimeout(
                    timeout = config.backendConnectivityCheckTimeout,
                    propertyName = "backendConnectivityCheckTimeout",
                )
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
    } catch (cause: Throwable) {
        resourceRegistry.close()
        throw cause
    }
}

private fun validateManagementDiagnosticsRoutePathCollision(config: LeaderElectionPluginConfig) {
    if (!config.managementRouteEnabled || !config.backendDiagnosticsRouteEnabled) return

    val managementPath = normalizeLeaderRoutePath(config.managementRoutePath)
    val diagnosticsPath = normalizeLeaderRoutePath(config.backendDiagnosticsRoutePath)
    require(managementPath != diagnosticsPath) {
        "managementRoutePath와 backendDiagnosticsRoutePath는 서로 다른 route여야 합니다: $managementPath"
    }
}

private fun Any.resolveBackendDiagnosticsProvider(): LeaderBackendDiagnosticsProvider? =
    when (this) {
        is LeaderBackendDiagnosticsProvider -> this
        is LeaderBackendDiagnosticsAware -> backendDiagnosticsProvider
        else                             -> null
    }

/**
 * 설치된 plugin 설정을 Application attributes에서 조회하는 key입니다.
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
 * 현재 Application에 설치된 `LeaderElectionPluginConfig`를 반환합니다. plugin이 없으면 예외를 던집니다.
 */
fun Application.leaderElectionPluginConfig(): LeaderElectionPluginConfig {
    return attributes.getOrNull(LeaderElectionConfigKey)
        ?: error("LeaderElectionPlugin 이 Application 에 설치되지 않았습니다.")
}

/**
 * plugin 이름과 logger를 보관하는 내부 공용 객체입니다.
 */
internal object LeaderElectionPluginInternals: KLogging() {
    const val NAME: String = "LeaderElection"
}

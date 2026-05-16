package io.bluetape4k.leader.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.util.AttributeKey

/**
 * Plugin entry point that integrates the coroutine-based leader election feature of
 * `bluetape4k-leader` into a Ktor 3.x application.
 *
 * ## Behavior / Contract
 * - Install with `install(LeaderElectionPlugin) { leaderElection = ... }`.
 * - Throws [IllegalArgumentException] at `install()` time if [LeaderElectionPluginConfig.leaderElection]
 *   is not configured (`requireNotNull` validation).
 * - Subscribes to [ApplicationStarted] / [ApplicationStopped] lifecycle events and logs at INFO level.
 * - The plugin itself does not start background jobs — combine with the [Application.leaderScheduled]
 *   extension function for periodic work.
 * - No explicit cleanup on shutdown — the `Application` CoroutineScope automatically cancels child coroutines.
 *
 * ```kotlin
 * fun Application.module() {
 *     install(LeaderElectionPlugin) {
 *         leaderElection = RedissonSuspendLeaderElector(redissonClient)
 *     }
 *
 *     leaderScheduled("daily-report", 1.hours) {
 *         reportService.generate()
 *     }
 * }
 * ```
 *
 * @see LeaderElectionPluginConfig
 * @see leaderScheduled
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

/**
 * Key used to identify [LeaderElectionPluginConfig] in the `Application` attributes store.
 * Assumes a single plugin instance per [Application].
 */
internal val LeaderElectionConfigKey: AttributeKey<LeaderElectionPluginConfig> =
    AttributeKey("io.bluetape4k.leader.ktor.LeaderElectionPluginConfig")

/**
 * Returns the [LeaderElectionPluginConfig] of the [LeaderElectionPlugin] installed in the current [Application].
 *
 * ## Behavior / Contract
 * - Throws [IllegalStateException] if the plugin has not been installed.
 *
 * @return The configuration object of the installed plugin
 * @throws IllegalStateException if the plugin is not installed
 */
fun Application.leaderElectionPluginConfig(): LeaderElectionPluginConfig {
    return attributes.getOrNull(LeaderElectionConfigKey)
        ?: error("LeaderElectionPlugin 이 Application 에 설치되지 않았습니다.")
}

/**
 * Internal object holding plugin constants and the logger. Not intended for external exposure;
 * exists as a workaround to apply the `KLogging` companion pattern in a top-level plugin definition.
 */
internal object LeaderElectionPluginInternals: KLogging() {
    const val NAME: String = "LeaderElection"
}

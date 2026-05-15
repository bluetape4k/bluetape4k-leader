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
 * Ktor 3.x 애플리케이션에서 `bluetape4k-leader` 의 코루틴 기반 리더 선출 기능을
 * 통합 사용할 수 있도록 제공하는 플러그인 진입점입니다.
 *
 * ## 동작/계약
 * - `install(LeaderElectionPlugin) { leaderElection = ... }` 형태로 설치합니다.
 * - [LeaderElectionPluginConfig.leaderElection] 미설정 시 `install()` 시점에
 *   [IllegalArgumentException] 이 발생합니다 (`requireNotNull` 검증).
 * - [ApplicationStarted] / [ApplicationStopped] 라이프사이클 이벤트를 구독하여 INFO 로그를 남깁니다.
 * - 플러그인 자체는 백그라운드 잡을 시작하지 않습니다 — [Application.leaderScheduled] 확장 함수와 결합하여 사용합니다.
 * - 종료 시 별도의 cleanup 동작은 없습니다 — `Application` CoroutineScope 가 자식 코루틴을 자동 취소합니다.
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
 * `Application` attributes 저장소에서 [LeaderElectionPluginConfig] 를 식별하는 키입니다.
 * 동일 [Application] 내 단일 플러그인 인스턴스를 가정합니다.
 */
internal val LeaderElectionConfigKey: AttributeKey<LeaderElectionPluginConfig> =
    AttributeKey("io.bluetape4k.leader.ktor.LeaderElectionPluginConfig")

/**
 * 현재 [Application] 에 설치된 [LeaderElectionPlugin] 의 [LeaderElectionPluginConfig] 를 조회합니다.
 *
 * ## 동작/계약
 * - 플러그인이 설치되지 않은 경우 [IllegalStateException] 을 던집니다.
 *
 * @return 설치된 플러그인의 설정 객체
 * @throws IllegalStateException 플러그인 미설치 시
 */
fun Application.leaderElectionPluginConfig(): LeaderElectionPluginConfig {
    return attributes.getOrNull(LeaderElectionConfigKey)
        ?: error("LeaderElectionPlugin 이 Application 에 설치되지 않았습니다.")
}

/**
 * 플러그인 내부 상수와 로거를 담는 객체. 외부 노출이 필요 없으나,
 * top-level 플러그인 정의에서 `KLogging` companion 패턴을 적용하기 위한 우회입니다.
 */
internal object LeaderElectionPluginInternals: KLogging() {
    const val NAME: String = "LeaderElection"
}

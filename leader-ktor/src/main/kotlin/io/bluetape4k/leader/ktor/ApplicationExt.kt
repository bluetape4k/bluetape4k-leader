package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.ktor.server.application.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * `Application` 호출은 Ktor integration 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun Application.leaderScheduled(
    lockName: String,
    period: Duration,
    leaderElection: SuspendLeaderElector = resolveLeaderElection(),
    action: suspend () -> Unit,
): Job {
    lockName.requireNotBlank("lockName")
    period.inWholeMilliseconds.requirePositiveNumber("period")
    attributes.getOrNull(LeaderElectionConfigKey)?.managementRegistry?.register(lockName)

    LeaderScheduledLogger.log.debug {
        "leaderScheduled 등록 — lockName=$lockName, period=$period"
    }

    return launch {
        while (isActive) {
            try {
                leaderElection.runIfLeader(lockName) { action() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LeaderScheduledLogger.log.warn(e) {
                    "leaderScheduled '$lockName' 실행 실패 — 다음 cycle 계속"
                }
            }
            delay(period)
        }
    }
}

/**
 * `Application` 호출은 Ktor integration 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
internal fun Application.resolveLeaderElection(): SuspendLeaderElector {
    val config = leaderElectionPluginConfig()
    return requireNotNull(config.leaderElection) {
        "LeaderElectionPlugin 의 leaderElection 이 설정되지 않았습니다."
    }
}

/**
 * `LeaderScheduledLogger`는 Ktor integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
internal object LeaderScheduledLogger: KLogging()

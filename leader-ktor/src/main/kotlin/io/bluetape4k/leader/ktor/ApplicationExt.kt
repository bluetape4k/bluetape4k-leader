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
 * 지정한 주기로 elector에게 lock 획득을 시도하고, 선출된 cycle에서만 `action`을 실행하는 application-owned Job을 등록합니다.
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

    val job = launch {
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

    // Plugin이 설치된 경우에만 application-owned scheduler Job을 registry에 귀속한다.
    // 명시적 elector overload는 기존 Application scope 동작을 그대로 사용한다.
    leaderElectionResourceRegistryOrNull()?.register(job)
    return job
}

/**
 * 설치된 `LeaderElectionPlugin`에서 `leaderElection`을 조회합니다. 설정되지 않았으면 `IllegalStateException`을 던집니다.
 */
internal fun Application.resolveLeaderElection(): SuspendLeaderElector {
    val config = leaderElectionPluginConfig()
    return requireNotNull(config.leaderElection) {
        "LeaderElectionPlugin 의 leaderElection 이 설정되지 않았습니다."
    }
}

/**
 * `leaderScheduled`의 등록·실행 실패를 기록하는 logger입니다.
 */
internal object LeaderScheduledLogger: KLogging()

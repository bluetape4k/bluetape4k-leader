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
 * 주기적으로 리더 전용 작업을 실행하는 Ktor 확장 함수입니다.
 *
 * ## 동작/계약
 * - [Application] 의 코루틴 스코프에서 [launch] 로 백그라운드 잡을 시작합니다 — `ApplicationStopped` 시 자동 취소됩니다.
 * - 각 cycle 마다 [SuspendLeaderElector.runIfLeader] 를 호출해 [lockName] 으로 단일 인스턴스만 [action] 을 실행합니다.
 * - [period] 간격으로 반복되며, [action] 예외는 WARN 로그 후 무시되고 다음 cycle 이 계속 진행됩니다 (poison-job 방지).
 * - [CancellationException] 은 항상 호출자에게 재전파되어 정상 취소됩니다.
 * - [leaderElection] 인자를 생략하면 [LeaderElectionPlugin] 의 설정에서 가져옵니다 — 플러그인 미설치 시 [IllegalStateException].
 * - Management route registration happens only when [LeaderElectionPlugin] is already installed.
 *   Install the plugin before calling [leaderScheduled] if `/management/leaderElection` should list
 *   this lock automatically.
 *
 * ## 입력 검증
 * - [lockName] 은 비어있지 않아야 합니다 (`IllegalArgumentException`).
 * - [period] 는 양수여야 합니다 (`IllegalArgumentException`).
 *
 * ```kotlin
 * fun Application.module() {
 *     install(LeaderElectionPlugin) {
 *         leaderElection = redissonElector
 *     }
 *     leaderScheduled("daily-report", period = 1.hours) {
 *         reportService.generate()
 *     }
 * }
 * ```
 *
 * @param lockName 리더 선출에 사용할 락 이름 (blank 금지)
 * @param period 다음 실행까지 대기할 간격 (양수)
 * @param leaderElection 사용할 [SuspendLeaderElector] — 미지정 시 플러그인 설정에서 조회
 * @param action 리더로 선출되었을 때 실행할 suspend 작업
 * @return 백그라운드 [Job] — 수동 취소 가능
 * @throws IllegalArgumentException [lockName] blank 또는 [period] 가 양수가 아닐 때
 * @throws IllegalStateException 인자 [leaderElection] 미지정 + 플러그인 미설치 시
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
 * [LeaderElectionPlugin] 의 설정에서 [SuspendLeaderElector] 를 조회합니다.
 *
 * ## 동작/계약
 * - 플러그인이 설치되지 않았거나 `leaderElection` 이 설정되지 않은 경우 [IllegalStateException] 을 던집니다.
 */
internal fun Application.resolveLeaderElection(): SuspendLeaderElector {
    val config = leaderElectionPluginConfig()
    return requireNotNull(config.leaderElection) {
        "LeaderElectionPlugin 의 leaderElection 이 설정되지 않았습니다."
    }
}

/** 파일 단위 로거 보관용 internal object. */
internal object LeaderScheduledLogger: KLogging()

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
 * Ktor extension function that runs a leader-only action on a recurring schedule.
 *
 * ## Behavior / Contract
 * - Starts a background job via [launch] in the [Application]'s coroutine scope — automatically
 *   cancelled on `ApplicationStopped`.
 * - Each cycle calls [SuspendLeaderElector.runIfLeader] with [lockName] so only a single instance
 *   executes [action].
 * - Repeats at [period] intervals. Exceptions from [action] are logged at WARN and suppressed so
 *   the next cycle continues (poison-job prevention).
 * - [CancellationException] is always re-propagated to the caller for normal cancellation.
 * - If [leaderElection] is omitted, it is resolved from the [LeaderElectionPlugin] configuration —
 *   throws [IllegalStateException] if the plugin is not installed.
 * - Management route registration happens only when [LeaderElectionPlugin] is already installed.
 *   Install the plugin before calling [leaderScheduled] if `/management/leaderElection` should list
 *   this lock automatically.
 *
 * ## Input Validation
 * - [lockName] must not be blank (`IllegalArgumentException`).
 * - [period] must be positive (`IllegalArgumentException`).
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
 * @param lockName Lock name used for leader election (must not be blank)
 * @param period Interval to wait between executions (must be positive)
 * @param leaderElection The [SuspendLeaderElector] to use — resolved from plugin config if not specified
 * @param action The suspend action to execute when elected as leader
 * @return Background [Job] — can be cancelled manually
 * @throws IllegalArgumentException if [lockName] is blank or [period] is not positive
 * @throws IllegalStateException if [leaderElection] is not specified and the plugin is not installed
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
 * Resolves the [SuspendLeaderElector] from the [LeaderElectionPlugin] configuration.
 *
 * ## Behavior / Contract
 * - Throws [IllegalStateException] if the plugin is not installed or `leaderElection` is not configured.
 */
internal fun Application.resolveLeaderElection(): SuspendLeaderElector {
    val config = leaderElectionPluginConfig()
    return requireNotNull(config.leaderElection) {
        "LeaderElectionPlugin 의 leaderElection 이 설정되지 않았습니다."
    }
}

/** 파일 단위 로거 보관용 internal object. */
internal object LeaderScheduledLogger: KLogging()

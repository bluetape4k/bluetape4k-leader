package io.bluetape4k.leader.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry
import io.bluetape4k.support.requirePositiveNumber
import io.ktor.server.engine.ApplicationEngine

/**
 * Ktor engine 종료 전에 application-owned management registry를 bounded drain합니다.
 *
 * registry가 drain deadline 안에 끝나지 않아도 engine stop은 계속 수행합니다. 이
 * helper는 외부 application scope를 취소하거나 `ApplicationStopping` listener를
 * 설치하지 않습니다.
 */
suspend fun ApplicationEngine.stopLeaderManagementGracefully(
    registry: SuspendLeaderManagementActionRegistry,
    gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
): Boolean {
    gracePeriodMillis.requirePositiveNumber("gracePeriodMillis")
    timeoutMillis.requirePositiveNumber("timeoutMillis")

    val drained = registry.closeAndDrain()
    if (!drained) {
        LeaderManagementShutdownLogger.log.warn {
            "leader management action registry drain timed out; continuing engine shutdown"
        }
    }
    this.stopSuspend(gracePeriodMillis, timeoutMillis)
    return drained
}

private const val DEFAULT_GRACE_PERIOD_MILLIS: Long = 1_000L
private const val DEFAULT_TIMEOUT_MILLIS: Long = 5_000L

private object LeaderManagementShutdownLogger : KLogging()

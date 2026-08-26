package io.bluetape4k.leader.spring.backend

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Spring의 동기 `@Bean` 경계에서 suspend backend 초기화를 수행합니다.
 *
 * 호출 thread에서 무제한 `runBlocking`을 사용하지 않도록 I/O dispatcher와 bounded timeout을
 * 고정하고, 초기화 실패·취소·timeout은 Spring bean 생성 오류로 그대로 전파합니다. timeout
 * 이후에는 자식 작업의 취소와 bounded cleanup join을 수행하여 cooperative 작업의 잔여 실행을
 * 정리합니다. non-cooperative 작업이 cleanup timeout 안에 끝나지 않으면 경고를 남기고 원래
 * 초기화 오류를 유지한 채 반환합니다.
 */
internal fun <T> createSuspendBackendBean(
    timeout: Duration = DEFAULT_SUSPEND_BEAN_INITIALIZATION_TIMEOUT,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    cleanupTimeout: Duration = DEFAULT_SUSPEND_BEAN_CLEANUP_TIMEOUT,
    block: suspend () -> T,
): T {
    require(timeout.isPositive() && timeout.isFinite()) {
        "suspend backend bean initialization timeout must be positive and finite: $timeout"
    }
    require(cleanupTimeout.isPositive() && cleanupTimeout.isFinite()) {
        "suspend backend bean cleanup timeout must be positive and finite: $cleanupTimeout"
    }
    return runBlocking {
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val task = scope.async { block() }
        try {
            withTimeout(timeout) { task.await() }
        } finally {
            withContext(NonCancellable) {
                val cleanupCompleted = withTimeoutOrNull(cleanupTimeout) {
                    task.cancelAndJoin()
                    true
                } ?: false
                scope.cancel()
                if (!cleanupCompleted) {
                    SuspendBeanInitializationLogger.log.warn {
                        "Suspend backend bean initialization cleanup did not complete within " +
                            "$cleanupTimeout; non-cooperative initialization may still be running"
                    }
                }
            }
        }
    }
}

internal val DEFAULT_SUSPEND_BEAN_INITIALIZATION_TIMEOUT: Duration = 10.seconds

/**
 * 초기화 timeout 이후 cooperative cleanup에 허용하는 짧은 grace window입니다.
 *
 * Spring startup failure가 dispatcher queue나 non-cooperative 작업 때문에 다시 무제한으로
 * 늘어나지 않도록 100ms로 제한하며, window를 넘긴 잔여 작업은 warning으로 관측합니다.
 */
internal val DEFAULT_SUSPEND_BEAN_CLEANUP_TIMEOUT: Duration = 100.milliseconds

private object SuspendBeanInitializationLogger : KLogging()

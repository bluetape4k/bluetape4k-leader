package io.bluetape4k.leader.spring.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Spring의 동기 `@Bean` 경계에서 suspend backend 초기화를 수행합니다.
 *
 * 호출 thread에서 무제한 `runBlocking`을 사용하지 않도록 I/O dispatcher와 bounded timeout을
 * 고정하고, 초기화 실패·취소·timeout은 Spring bean 생성 오류로 그대로 전파합니다.
 */
internal fun <T> createSuspendBackendBean(
    timeout: Duration = DEFAULT_SUSPEND_BEAN_INITIALIZATION_TIMEOUT,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend () -> T,
): T {
    require(timeout.isPositive() && timeout.isFinite()) {
        "suspend backend bean initialization timeout must be positive and finite: $timeout"
    }
    return runBlocking {
        val result = CompletableDeferred<T>()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var job: Job? = null
        try {
            withTimeout(timeout) {
                job = scope.launch {
                    runCatching { block() }.fold(
                        onSuccess = { result.complete(it) },
                        onFailure = { result.completeExceptionally(it) },
                    )
                }
                result.await()
            }
        } finally {
            job?.cancel()
            scope.cancel()
        }
    }
}

internal val DEFAULT_SUSPEND_BEAN_INITIALIZATION_TIMEOUT: Duration = 10.seconds

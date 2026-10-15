package io.bluetape4k.leader.exposed.r2dbc.lock

import kotlinx.coroutines.CancellationException

/**
 * Exposed/R2DBC driver마다 예외를 감싸는 방식이 달라 호출자가 `Exception` 전체를 자체 fallback으로 해석합니다.
 * 코루틴 취소는 DB 실패로 바꾸지 않도록 먼저 재전파합니다.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runR2dbcLockOperationPreservingCancellation(
    onFailure: (Exception) -> T,
    operation: suspend () -> T,
): T =
    try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
    }

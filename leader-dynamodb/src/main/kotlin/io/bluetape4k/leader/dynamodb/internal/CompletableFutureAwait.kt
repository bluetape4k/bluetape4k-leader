package io.bluetape4k.leader.dynamodb.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// 공통 코루틴 bridge로 이동할 후보입니다.
internal suspend fun <T> CompletableFuture<T>.awaitWithoutCancellingFuture(
    onCancellation: (CompletableFuture<T>) -> Unit = {},
): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { value, failure ->
            if (!cont.isActive) {
                return@whenComplete
            }
            if (failure == null) {
                cont.resume(value)
            } else {
                cont.resumeWithException((failure as? CompletionException)?.cause ?: failure)
            }
        }
        cont.invokeOnCancellation { onCancellation(this) }
    }

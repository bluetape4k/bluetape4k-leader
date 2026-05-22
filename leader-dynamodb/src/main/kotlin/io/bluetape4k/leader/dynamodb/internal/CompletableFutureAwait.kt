package io.bluetape4k.leader.dynamodb.internal

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun <T> CompletableFuture<T>.awaitWithoutCancellingFuture(
    onCancellation: (CompletableFuture<T>) -> Unit,
): T =
    suspendCancellableCoroutine { cont ->
        whenComplete { value, failure ->
            if (!cont.isActive) {
                return@whenComplete
            }
            if (failure == null) {
                cont.resume(value)
            } else {
                cont.resumeWithException((failure as? java.util.concurrent.CompletionException)?.cause ?: failure)
            }
        }
        cont.invokeOnCancellation { onCancellation(this) }
    }

package io.bluetape4k.leader

import io.bluetape4k.support.checkNotNull
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException


fun Throwable?.unwrapCompletionException(): Throwable? =
    (this as? CompletionException)?.cause ?: this

fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
    val cause = unwrapCompletionException().checkNotNull("cause")
    if (cause is CancellationException) {
        throw cause
    }
    return LeaderRunResult.ActionFailed(cause)
}

fun Throwable.asCompletionException(): CompletionException =
    this as? CompletionException ?: CompletionException(this)

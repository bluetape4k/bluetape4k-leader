package io.bluetape4k.leader.exposed.jdbc.internal

import java.util.concurrent.CompletionException

internal fun Throwable.unwrapCompletionCause(): Throwable =
    if (this is CompletionException && cause != null) cause!! else this

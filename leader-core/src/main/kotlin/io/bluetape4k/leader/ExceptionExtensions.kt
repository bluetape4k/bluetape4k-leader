package io.bluetape4k.leader

import java.util.concurrent.CompletionException


fun Throwable?.unwrapCompletionException(): Throwable? =
    if (this is CompletionException && cause != null) cause else this

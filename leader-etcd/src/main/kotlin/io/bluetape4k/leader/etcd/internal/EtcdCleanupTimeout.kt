package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.concurrent.get
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal fun etcdCleanupTimeout(waitTime: Duration, retryDelay: Duration): Duration =
    if (waitTime >= retryDelay) waitTime else retryDelay

internal fun <T> CompletableFuture<T>.getWithinEtcdCleanupTimeout(timeout: Duration): T =
    get(timeout.inWholeNanoseconds.coerceAtLeast(1L).nanoseconds)

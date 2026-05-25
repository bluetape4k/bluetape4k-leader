package io.bluetape4k.leader.etcd.internal

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

internal fun etcdCleanupTimeout(waitTime: Duration, retryDelay: Duration): Duration =
    if (waitTime >= retryDelay) waitTime else retryDelay

internal fun <T> CompletableFuture<T>.getWithinEtcdCleanupTimeout(timeout: Duration): T =
    get(timeout.inWholeNanoseconds.coerceAtLeast(1L), TimeUnit.NANOSECONDS)

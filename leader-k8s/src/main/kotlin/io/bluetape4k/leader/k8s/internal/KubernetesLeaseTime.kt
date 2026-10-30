package io.bluetape4k.leader.k8s.internal

import io.bluetape4k.support.requirePositiveDuration
import kotlin.time.Duration

internal fun Duration.toLeaseDurationSeconds(name: String): Int {
    require(isFinite()) { "$name must be finite. $name=$this" }
    requirePositiveDuration(name)

    val millis = inWholeMilliseconds.coerceAtLeast(1L)
    val seconds = (millis + 999L) / 1_000L
    require(seconds <= Int.MAX_VALUE) { "$name is too large for Kubernetes leaseDurationSeconds. $name=$this" }
    return seconds.toInt()
}

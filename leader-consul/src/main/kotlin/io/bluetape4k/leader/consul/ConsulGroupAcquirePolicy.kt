package io.bluetape4k.leader.consul

import java.util.concurrent.ThreadLocalRandom

internal const val CONSUL_GROUP_SLOT_PROBE_LIMIT: Int = 3

internal fun consulGroupSlotProbeCount(maxLeaders: Int): Int =
    maxLeaders.coerceAtMost(CONSUL_GROUP_SLOT_PROBE_LIMIT).coerceAtLeast(1)

internal fun consulRemainingMillis(deadlineNanos: Long): Long =
    ((deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L).coerceAtLeast(1L)

internal fun consulGroupAcquireDelayMillis(deadlineNanos: Long): Long {
    val jitterMillis = ThreadLocalRandom.current().nextLong(25L, 76L)
    return minOf(jitterMillis, consulRemainingMillis(deadlineNanos)).coerceAtLeast(1L)
}

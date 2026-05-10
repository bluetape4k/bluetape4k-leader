package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * 리더 lease를 백엔드 소유권 조건으로 주기 연장하는 공통 watchdog helper입니다.
 *
 * ## 계약
 * - [extend]는 반드시 현재 owner token/thread/instance 조건을 확인해야 합니다.
 * - [extend]가 `false`를 반환하거나 예외를 던지면 watchdog은 반복을 중단합니다.
 * - 호출자는 action 종료/예외/취소 release path에서 반환된 [AutoCloseable]을 닫아야 합니다.
 *
 * ```kotlin
 * val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime) {
 *     lock.extend(options.leaseTime)
 * }
 * try {
 *     action()
 * } finally {
 *     watchdog.close()
 * }
 * ```
 */
object LeaderLeaseAutoExtender : KLogging() {

    private val threadSeq = AtomicInteger()
    private val scheduler = ScheduledThreadPoolExecutor(DEFAULT_WATCHDOG_THREADS) { runnable ->
        Thread(runnable, "bluetape4k-leader-lease-watchdog-${threadSeq.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.apply {
        removeOnCancelPolicy = true
    }

    /**
     * [enabled]가 true이면 lease 연장 watchdog을 시작하고, false이면 no-op closeable을 반환합니다.
     */
    fun start(
        enabled: Boolean,
        leaseTime: Duration,
        extend: () -> Boolean,
    ): AutoCloseable {
        if (!enabled) {
            return NoopCloseable
        }

        val closed = AtomicBoolean(false)
        val period = renewalPeriod(leaseTime)
        lateinit var future: ScheduledFuture<*>

        future = scheduler.scheduleWithFixedDelay(
            {
                if (closed.get()) {
                    future.cancel(false)
                    return@scheduleWithFixedDelay
                }

                val extended = runCatching { extend() }
                    .onFailure { log.warn(it) { "leader.lease.auto-extend.failed" } }
                    .getOrDefault(false)

                if (!extended && closed.compareAndSet(false, true)) {
                    log.warn { "leader.lease.auto-extend.stopped reason=NOT_OWNER" }
                    future.cancel(false)
                }
            },
            period.inWholeMilliseconds,
            period.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )

        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false)
            }
        }
    }

    /**
     * [leaseTime] 기준 watchdog 반복 주기를 계산합니다.
     */
    fun renewalPeriod(leaseTime: Duration): Duration {
        val third = leaseTime / 3
        return if (third > MIN_RENEWAL_PERIOD) third else MIN_RENEWAL_PERIOD
    }

    private object NoopCloseable : AutoCloseable {
        override fun close() = Unit
    }

    private const val DEFAULT_WATCHDOG_THREADS = 2
    private val MIN_RENEWAL_PERIOD = 25.milliseconds
}

package io.bluetape4k.leader

import io.bluetape4k.leader.ExtendOutcome.BackendError
import io.bluetape4k.leader.ExtendOutcome.Extended
import io.bluetape4k.leader.ExtendOutcome.NotHeld
import io.bluetape4k.leader.ExtendOutcome.WrongThread
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.CoreBackendErrorClassifier
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
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
 * ## ExtendDelegate 기반 권장 사용 (R2 watchdog skip)
 * ```kotlin
 * val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
 * try {
 *     action()
 * } finally {
 *     watchdog.close()
 * }
 * ```
 *
 * ## 기존 Boolean 람다 사용 (Deprecated)
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
    private val scheduler = Executors.newScheduledThreadPool(DEFAULT_WATCHDOG_THREADS) { runnable ->
        Thread(runnable, "bluetape4k-leader-lease-watchdog-${threadSeq.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    /**
     * [enabled]가 true이면 [ExtendDelegate] 를 이용한 lease 연장 watchdog을 시작합니다.
     *
     * ## R2 watchdog skip
     * tick 마다 `delegate.lastExtendDeadline.get()` 를 읽어,
     * `now + cadence < lastExtendDeadline` 이면 backend extend 호출을 skip 합니다.
     * User 가 `LockExtender.extendActiveLock(60s)` 를 호출한 직후 watchdog 이 lease 를
     * 작은 값으로 silently 축소하는 split-brain 을 차단합니다.
     *
     * ## 종료 조건
     * - [ExtendOutcome.NotHeld] / [ExtendOutcome.WrongThread] → watchdog 중단 (소유권 상실)
     * - [ExtendOutcome.BackendError] (TRANSIENT) → WARN log + 계속 (재시도)
     * - [ExtendOutcome.BackendError] (NON_TRANSIENT / FATAL) → WARN log + watchdog 중단
     * - [AutoCloseable.close] 호출 → 중단
     *
     * @param enabled `false` 이면 no-op closeable 반환
     * @param leaseTime backend lease 시간 — cadence = leaseTime / 3 (최소 [MIN_RENEWAL_PERIOD])
     * @param delegate backend extend SPI — [ExtendDelegate.extend] + [ExtendDelegate.lastExtendDeadline]
     */
    fun start(
        enabled: Boolean,
        leaseTime: Duration,
        delegate: ExtendDelegate,
    ): AutoCloseable {
        if (!enabled) {
            return NoopCloseable
        }

        val closed = AtomicBoolean(false)
        val cadence = renewalPeriod(leaseTime)
        lateinit var future: ScheduledFuture<*>

        future = scheduler.scheduleWithFixedDelay(
            {
                if (closed.get()) {
                    future.cancel(false)
                    return@scheduleWithFixedDelay
                }

                // R2 mitigation: user 가 explicit extend 를 호출했으면 watchdog skip
                val deadline = delegate.lastExtendDeadline.get()
                if (deadline != null && Instant.now().plusMillis(cadence.inWholeMilliseconds).isBefore(deadline)) {
                    return@scheduleWithFixedDelay
                }

                val outcome = try {
                    delegate.extend(leaseTime)
                } catch (ex: Exception) {
                    log.warn(ex) { "leader.lease.auto-extend.failed" }
                    BackendError(ex)
                }

                when (outcome) {
                    is Extended -> { /* 정상 연장 — 계속 */ }
                    is NotHeld, is WrongThread -> {
                        log.warn { "leader.lease.auto-extend.stopped reason=$outcome" }
                        if (closed.compareAndSet(false, true)) {
                            future.cancel(false)
                        }
                    }
                    is BackendError -> {
                        val kind = CoreBackendErrorClassifier.classify(outcome.cause)
                            ?: BackendErrorKind.NON_TRANSIENT
                        when (kind) {
                            BackendErrorKind.TRANSIENT -> {
                                // 재시도 가능 — WARN log 후 계속
                                log.warn(outcome.cause) { "leader.lease.auto-extend.transient-error — retrying. cause=${outcome.cause.message}" }
                            }
                            BackendErrorKind.NON_TRANSIENT, BackendErrorKind.FATAL -> {
                                // 영구 오류 또는 치명적 오류 — watchdog 중단
                                log.warn(outcome.cause) { "leader.lease.auto-extend.stopped reason=BACKEND_ERROR kind=$kind cause=${outcome.cause.message}" }
                                if (closed.compareAndSet(false, true)) {
                                    future.cancel(false)
                                }
                            }
                        }
                    }
                }
            },
            cadence.inWholeMilliseconds,
            cadence.inWholeMilliseconds,
            TimeUnit.MILLISECONDS,
        )

        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false)
            }
        }
    }

    /**
     * [enabled]가 true이면 lease 연장 watchdog을 시작하고, false이면 no-op closeable을 반환합니다.
     *
     * @deprecated [ExtendDelegate] 를 받는 [start] 사용 권장.
     * R2 watchdog skip semantics (`lastExtendDeadline` 검사) 가 없음.
     * T7~T13 에서 각 backend elector 가 [ExtendDelegate] 로 migration 후 이 overload 제거 예정.
     */
    @Deprecated(
        message = "Use start(enabled, leaseTime, delegate: ExtendDelegate) for R2 watchdog skip semantics. " +
            "This overload will be removed after T7–T13 backend migration.",
        replaceWith = ReplaceWith("start(enabled, leaseTime, delegate)"),
    )
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

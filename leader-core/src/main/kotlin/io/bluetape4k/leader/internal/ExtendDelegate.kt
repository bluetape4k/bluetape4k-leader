package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * Backend lock 의 atomic extend 를 단일 reference 로 제공하는 internal SPI.
 *
 * [LeaderLockHandle.Real.extendDelegate] 와 [LeaderLeaseAutoExtender] (Watchdog) 가 동일 인스턴스를
 * 공유 — race-free 보장 (Step 3-P R2 mitigation).
 *
 * ## 동작/계약
 * - `extend` 는 backend atomic extend 호출 — 동기 backend (Lettuce sync, Redisson, Hazelcast, Exposed JDBC, ZK) 가 구현
 * - `extendSuspend` default 는 sync `extend` 직접 호출 — **blocking backend 는 반드시 override** 하여
 *   `withContext(Dispatchers.IO)` + `coroutineContext.ensureActive()` 사용 (R9 mitigation)
 * - `lastExtendDeadline` 은 user `LockExtender.extendActiveLock(d)` 호출 시점의 expire deadline 추적 (R2 mitigation).
 *   Watchdog tick 이 `now() + watchdogCadence < lastExtendDeadline.get()` 이면 backend extend 호출 skip.
 *
 * ## R2 Watchdog skip semantics (Step 3-P)
 * User 가 `extend(60s)` 호출 → `lastExtendDeadline = now + 60s` 갱신.
 * Watchdog cadence = leaseTime/3 (e.g. 10s for 30s lease). Tick 시 `now + 10s < deadline` 이면 skip.
 * → user-extended lease 가 watchdog 에 의해 silently 축소되는 split-brain 차단.
 */
internal interface ExtendDelegate {

    fun extend(lockAtMostFor: Duration): ExtendOutcome

    /**
     * Suspend extend.
     *
     * Default 구현은 sync [extend] 를 직접 호출 — **non-blocking / native suspend backend 전용**.
     * Blocking backend (Lettuce sync, Hazelcast IMap, Exposed JDBC, Redisson 등) 는 반드시 override 하여
     * `withContext(Dispatchers.IO)` + `coroutineContext.ensureActive()` 사용.
     *
     * AC-21: blocking backend 의 [ExtendDelegate.extendSuspend] 가 default 호출 0회 (소스 grep verify).
     */
    suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = extend(lockAtMostFor)

    /**
     * 현재 토큰이 backend 에 살아있는지 확인.
     */
    fun isHeld(): Boolean

    /**
     * User explicit extend 호출의 deadline 추적 (R2 mitigation).
     *
     * `LockExtender.extendActiveLock(d)` 가 set: `now() + d`.
     * Watchdog 가 read: `now() + cadence < lastExtendDeadline.get()` 이면 backend 호출 skip.
     *
     * **구현 규칙**: 반드시 저장된 `AtomicReference<Instant>` 인스턴스를 반환해야 한다.
     * `get()` 마다 새 객체를 생성하면 `.set()` 호출이 버려져 R2 mitigation 이 무효화된다.
     * 구현 예:
     * ```kotlin
     * private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
     * override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
     * ```
     *
     * 초기값 `Instant.EPOCH` — watchdog 가 항상 진행 (user explicit extend 없음).
     */
    val lastExtendDeadline: AtomicReference<Instant>
}

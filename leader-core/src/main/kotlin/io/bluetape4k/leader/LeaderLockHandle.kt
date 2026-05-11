package io.bluetape4k.leader

import io.bluetape4k.leader.internal.ExtendDelegate
import java.io.Serializable
import kotlin.time.Duration

/**
 * 활성 lock 의 handle. AOP aspect 가 push 하고 `LockAssert` / `LockExtender` 가 read.
 *
 * ## Variants
 * - [Real] — 실제 backend 보유. `extend()` / `extendSuspend()` 호출 가능
 * - [FailOpen] — fail-open sentinel. 외부 정의에서 extend 항상 [ExtendOutcome.NotHeld] 반환
 *
 * ## 동작/계약
 * - **소스 API 레벨에서** 외부 생성 차단 — `internal constructor`. AOP aspect 또는 elector 만 생성.
 *   ⚠️ `internal` 은 reflection / security boundary 아님 — 신뢰된 caller 한정 source API 차단 의미.
 * - sealed class 의 `when` 분기는 exhaustive — `else` branch 없음.
 *
 * ## Example
 * ```kotlin
 * when (val handle = LockStateHolder.peekSync()) {
 *     is LeaderLockHandle.Real -> handle.extend(60.seconds)
 *     is LeaderLockHandle.FailOpen -> error("fail-open scope — no real lock")
 *     null -> error("no active scope")
 * }
 * ```
 */
sealed class LeaderLockHandle : Serializable {

    abstract val identity: LockIdentity
    val lockName: String get() = identity.lockName
    abstract val reentryDepth: Int
    val isReentrant: Boolean get() = reentryDepth > 0

    /**
     * 다른 [LockIdentity] 와 같은 lock 인지 비교 (reentrant peek 용).
     *
     * [LockIdentity.equals] 가 `factoryBeanName` 을 제외하므로 sync/suspend factory 가 달라도
     * 동일 lock 이면 reentrant pass-through (R3 mitigation).
     */
    fun matchesIdentity(other: LockIdentity): Boolean = identity == other

    /**
     * 실제 backend lock 보유 handle.
     *
     * @property identity full lock identity (reentrant peek 비교 단위)
     * @property token Base58(8) lock 토큰 — backend atomic extend guard
     * @property acquiredAtNanos `System.nanoTime()` 기준 획득 시각 — minLeaseTime 계산용
     * @property slotId group lock 의 permit/slot 식별자. single 이면 `null`
     * @property acquiringThreadId Redisson thread-bound extend 검증용. 비-Redisson 은 `null`.
     *   **ownership 비교에 사용 금지** (R6-P2) — Redisson cross-thread debug 정보 only.
     * @property reentryDepth 0 = outermost 실 락, >0 = reentrant passthrough copy
     * @property extendDelegate watchdog 와 동일 reference 공유 (AC-15)
     */
    class Real internal constructor(
        override val identity: LockIdentity,
        val token: String,
        val acquiredAtNanos: Long,
        val slotId: String? = null,
        val acquiringThreadId: Long? = null,
        override val reentryDepth: Int = 0,
        /** ⚠️ Backend module / aspect 전용 SPI — 애플리케이션 코드에서 직접 접근 금지. */
        val extendDelegate: ExtendDelegate,
    ) : LeaderLockHandle() {

        /**
         * Backend atomic extend 호출.
         *
         * Reentrant passthrough copy 일 때도 outer 의 [extendDelegate] 를 그대로 들고 있으므로
         * **inner 에서 호출 시 outer/backend lease 가 갱신** (R5-F3 / SF11).
         */
        fun extend(lockAtMostFor: Duration): ExtendOutcome = extendDelegate.extend(lockAtMostFor)

        /** Suspend variant — backend 가 suspend native 면 non-blocking, 아니면 `withContext(IO)` override. */
        suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
            extendDelegate.extendSuspend(lockAtMostFor)

        /**
         * 현재 token 이 backend 에 살아있는지 확인. lease 만료 후 takeover 발생 시 `false`.
         */
        fun isStillHeld(): Boolean = extendDelegate.isHeld()

        /**
         * Reentrant passthrough copy 생성.
         *
         * **inner extend 는 outer 의 [extendDelegate] 그대로 호출 → outer/backend lease 갱신** (R5-F3 / SF11).
         */
        internal fun withReentryDepth(n: Int): Real {
            require(n >= 0) { "reentryDepth must be >= 0: $n" }
            return Real(identity, token, acquiredAtNanos, slotId, acquiringThreadId, n, extendDelegate)
        }

        // equals/hashCode 는 (identity, token, reentryDepth, slotId) 기반.
        // acquiringThreadId 는 ownership 비교에 사용 금지 (R6-P2).
        // extendDelegate 는 reference 비교 무의미 — 제외.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Real) return false
            return identity == other.identity &&
                token == other.token &&
                reentryDepth == other.reentryDepth &&
                slotId == other.slotId
        }

        override fun hashCode(): Int {
            var result = identity.hashCode()
            result = 31 * result + token.hashCode()
            result = 31 * result + reentryDepth
            result = 31 * result + (slotId?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "LeaderLockHandle.Real(identity=$identity, token='$token', reentryDepth=$reentryDepth, slotId=$slotId)"

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * Fail-open sentinel — `failureMode = FAIL_OPEN_RUN` 시 backend 미보유 상태에서 body 실행.
     *
     * `LockAssert.assertLocked()` 는 throw — fail-open scope 안에서는 lock 미보유.
     * `LockExtender.extendActiveLock(d)` 는 `false` 반환 + WARN log.
     */
    class FailOpen internal constructor(
        override val identity: LockIdentity,
    ) : LeaderLockHandle() {
        override val reentryDepth: Int = 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FailOpen) return false
            return identity == other.identity
        }

        override fun hashCode(): Int = identity.hashCode()

        override fun toString(): String = "LeaderLockHandle.FailOpen(identity=$identity)"

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        /**
         * Backend module 전용 factory. AOP aspect / elector 가 호출.
         *
         * ⚠️ 애플리케이션 코드에서 직접 호출 금지 — 외부 사용자는 `LockAssert` / `LockExtender` 만 사용.
         * 본 factory 는 `leader-redis-lettuce`, `leader-redis-redisson`, `leader-mongodb`, `leader-exposed-jdbc`,
         * `leader-exposed-r2dbc`, `leader-hazelcast`, `leader-zookeeper` 등 backend module 에서 사용.
         */
        fun real(
            identity: LockIdentity,
            token: String,
            acquiredAtNanos: Long,
            slotId: String? = null,
            acquiringThreadId: Long? = null,
            reentryDepth: Int = 0,
            extendDelegate: ExtendDelegate,
        ): Real = Real(identity, token, acquiredAtNanos, slotId, acquiringThreadId, reentryDepth, extendDelegate)

        /**
         * Backend module / aspect 전용 sentinel factory. `failureMode = FAIL_OPEN_RUN` 시 사용.
         *
         * ⚠️ 애플리케이션 코드에서 직접 호출 금지.
         */
        fun failOpen(identity: LockIdentity): FailOpen = FailOpen(identity)
    }
}

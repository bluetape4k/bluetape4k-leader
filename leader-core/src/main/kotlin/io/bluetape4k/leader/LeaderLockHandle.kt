package io.bluetape4k.leader

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.support.hashOf
import io.bluetape4k.support.requireGe
import java.io.Serializable
import kotlin.time.Duration

/**
 * `LeaderLockHandle`은 현재 blocking, coroutine 또는 virtual-thread 실행 문맥이 가진 lock 소유권을 나타냅니다.
 *
 */
sealed class LeaderLockHandle: Serializable {

    abstract val identity: LockIdentity
    val lockName: String get() = identity.lockName
    abstract val reentryDepth: Int
    val isReentrant: Boolean get() = reentryDepth > 0

    /**
     * 이 handle의 election identity가 지정한 identity와 같은지 비교합니다.
     * @param other 비교할 lock 이름·kind·slot 식별자입니다.
     * @return identity가 같으면 `true`입니다.
     */
    fun matchesIdentity(other: LockIdentity): Boolean = identity == other

    /**
     * backend에서 실제로 획득한 lock을 연장하고 소유권을 확인할 수 있는 handle입니다.
     * @property identity lock 이름, token, kind, slot 정보를 묶은 소유권 식별자입니다.
     * @property token backend가 이 lock 소유권을 확인하는 token입니다. 문자열 출력 시 가려집니다.
     * @property acquiredAtNanos lock 획득 시점의 monotonic clock 값(나노초)입니다. 절대 시각으로 해석하면 안 됩니다.
     * @property slotId group election에서 획득한 slot 식별자입니다. 단일 election이면 `null`입니다.
     * @property acquiringThreadId lock 획득 스레드 ID입니다. thread-bound 소유권이 없으면 `null`입니다.
     * @property reentryDepth 이 실행 문맥에서 중첩 획득한 횟수입니다. 최초 획득은 `0`이며 음수는 허용되지 않습니다.
     * @property extendDelegate backend의 lease 연장·소유권 확인 구현입니다.
     * @property auditLeaderId audit에 연결할 leader ID입니다. 식별 정보가 없으면 `null`입니다.
     */
    class Real internal constructor(
        override val identity: LockIdentity,
        val token: String,
        val acquiredAtNanos: Long,
        val slotId: String? = null,
        val acquiringThreadId: Long? = null,
        override val reentryDepth: Int = 0,
        val extendDelegate: ExtendDelegate,
        val auditLeaderId: String? = null,
    ): LeaderLockHandle() {

        /**
         * 현재 lock의 lease를 지정한 TTL로 연장합니다.
         *
         * @param lockAtMostFor 연장 후 유지할 lease TTL입니다.
         * @return 연장 결과를 `Extended`, `NotHeld`, `Rejected` 등의 상태로 반환합니다.
         */
        fun extend(lockAtMostFor: Duration): ExtendOutcome = extendDelegate.extend(lockAtMostFor)

        /**
         * suspend backend를 통해 현재 lock의 lease를 지정한 TTL로 연장합니다.
         *
         * @param lockAtMostFor 연장 후 유지할 lease TTL입니다.
         * @return 연장 결과를 `Extended`, `NotHeld`, `Rejected` 등의 상태로 반환합니다.
         */
        suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
            extendDelegate.extendSuspend(lockAtMostFor)

        /**
         * backend에 현재 lock 소유권이 남아 있는지 조회합니다.
         *
         * @return backend가 lock을 계속 보유한다고 확인하면 `true`입니다.
         */
        fun isStillHeld(): Boolean = extendDelegate.isHeld()

        /**
         * suspend backend를 사용하거나 동기 fallback으로 현재 lock 소유권을 조회합니다.
         *
         * @return backend가 lock을 계속 보유한다고 확인하면 `true`입니다.
         */
        suspend fun isStillHeldSuspend(): Boolean =
            if (extendDelegate is SuspendExtendDelegate)
                extendDelegate.isHeldSuspend()
            else extendDelegate.isHeld()

        /**
         * 기존 소유권 정보는 유지하고 재진입 깊이만 바꾼 handle을 반환합니다.
         *
         * @param n 새 재진입 깊이입니다. `0` 이상이어야 합니다.
         * @return 지정한 깊이를 가진 새 handle입니다.
         */
        internal fun withReentryDepth(n: Int): Real {
            n.requireGe(0, "n")
            return Real(identity, token, acquiredAtNanos, slotId, acquiringThreadId, n, extendDelegate, auditLeaderId)
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

        override fun hashCode(): Int = hashOf(identity, token, reentryDepth, slotId)
        override fun toString(): String = ToStringBuilder(this)
            .add("identity", identity)
            .add("token", REDACTED)
            .add("reentryDepth", reentryDepth)
            .add("slotId", slotId)
            .add("acquiringThreadId", acquiringThreadId)
            .toString()

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * fail-open 경로가 활성화된 실행 문맥의 sentinel handle입니다. 실제 backend lock을 보유하지 않습니다.
     *
     * @property identity fail-open 처리를 적용한 lock의 이름·kind·slot 식별자입니다.
     */
    class FailOpen internal constructor(
        override val identity: LockIdentity,
    ): LeaderLockHandle() {
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
         * backend lock 소유권을 나타내는 실제 handle을 생성합니다.
         *
         * @param identity lock 이름, election kind, slot 식별자를 묶은 값입니다.
         * @param token backend가 lock 소유권을 검증하는 값입니다.
         * @param acquiredAtNanos lock 획득 시점의 monotonic clock 값(나노초)입니다.
         * @param slotId group election에서 획득한 slot ID입니다. 단일 election이면 `null`입니다.
         * @param acquiringThreadId 소유권이 thread-bound인 경우의 획득 스레드 ID입니다.
         * @param reentryDepth 같은 문맥에서 중첩 획득한 횟수입니다. 기본값은 `0`입니다.
         * @param extendDelegate backend의 lease 연장·소유권 확인 구현입니다.
         * @param auditLeaderId audit에 연결할 leader ID입니다. 기본값은 `null`입니다.
         * @return 전달한 소유권과 delegate를 보유한 `Real` handle입니다.
         */
        fun real(
            identity: LockIdentity,
            token: String,
            acquiredAtNanos: Long,
            slotId: String? = null,
            acquiringThreadId: Long? = null,
            reentryDepth: Int = 0,
            extendDelegate: ExtendDelegate,
            auditLeaderId: String? = null,  // positional 끝: backward compatibility를 위해 기본값은 null입니다.
        ): Real = Real(
            identity,
            token,
            acquiredAtNanos,
            slotId,
            acquiringThreadId,
            reentryDepth,
            extendDelegate,
            auditLeaderId
        )

        /**
         * fail-open 경로에서 실제 lock 대신 사용할 sentinel handle을 만듭니다.
         *
         * @param identity fail-open 처리를 적용한 lock의 이름·kind·slot 식별자입니다.
         * @return 실제 backend 소유권이 없는 `FailOpen` sentinel입니다.
         */
        fun failOpen(identity: LockIdentity): FailOpen = FailOpen(identity)
    }
}

package io.bluetape4k.leader

import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.support.requireGe
import java.io.Serializable
import kotlin.time.Duration

/**
 * `LeaderLockHandle`는 실행 컨텍스트에 캡처되는 lock handle입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
sealed class LeaderLockHandle : Serializable {

    abstract val identity: LockIdentity
    val lockName: String get() = identity.lockName
    abstract val reentryDepth: Int
    val isReentrant: Boolean get() = reentryDepth > 0

    /**
     * `matchesIdentity` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param other `other` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun matchesIdentity(other: LockIdentity): Boolean = identity == other

    /**
     * `Real` 선언은 leader election 계약에서 사용되는 class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property identity lock 이름, token, kind, slot 정보를 묶은 소유권 식별자입니다.
     * @property token backend lock을 해제하거나 검증할 때 사용하는 소유권 token입니다.
     * @property acquiredAtNanos `acquiredAtNanos` 호출 또는 상태 계산에 필요한 값입니다.
     * @property slotId group election backend가 slot을 식별할 때 쓰는 값입니다.
     * @property acquiringThreadId `acquiringThreadId` 호출 또는 상태 계산에 필요한 값입니다.
     * @property reentryDepth 같은 실행 컨텍스트에서 재진입한 깊이입니다.
     * @property extendDelegate `extendDelegate` 호출 또는 상태 계산에 필요한 값입니다.
     * @property auditLeaderId `auditLeaderId` 호출 또는 상태 계산에 필요한 값입니다.
     */
    class Real internal constructor(
        override val identity: LockIdentity,
        val token: String,
        val acquiredAtNanos: Long,
        val slotId: String? = null,
        val acquiringThreadId: Long? = null,
        override val reentryDepth: Int = 0,
        /**
         * `extendDelegate` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
         */
        val extendDelegate: ExtendDelegate,
        val auditLeaderId: String? = null,
    ) : LeaderLockHandle() {

        /**
         * `extend`는 현재 lock 소유권을 확인한 뒤 lease를 연장합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun extend(lockAtMostFor: Duration): ExtendOutcome = extendDelegate.extend(lockAtMostFor)

        /**
         * `extendSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
            extendDelegate.extendSuspend(lockAtMostFor)

        /**
         * `isStillHeld` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun isStillHeld(): Boolean = extendDelegate.isHeld()

        /**
         * `isStillHeldSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        suspend fun isStillHeldSuspend(): Boolean =
            if (extendDelegate is SuspendExtendDelegate) extendDelegate.isHeldSuspend() else extendDelegate.isHeld()

        /**
         * `withReentryDepth` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param n `n` 호출 또는 상태 계산에 필요한 값입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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

        override fun hashCode(): Int {
            var result = identity.hashCode()
            result = 31 * result + token.hashCode()
            result = 31 * result + reentryDepth
            result = 31 * result + (slotId?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String = buildString {
            append("LeaderLockHandle.Real(identity=$identity, token=<redacted>, reentryDepth=$reentryDepth, slotId=$slotId")
            if (auditLeaderId != null) append(", auditLeaderId='$auditLeaderId'")
            append(")")
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * `FailOpen` 선언은 leader election 계약에서 사용되는 class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property identity lock 이름, token, kind, slot 정보를 묶은 소유권 식별자입니다.
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
         * `real` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param identity lock 이름, token, kind, slot 정보를 묶은 소유권 식별자입니다.
         * @param token backend lock을 해제하거나 검증할 때 사용하는 소유권 token입니다.
         * @param acquiredAtNanos `acquiredAtNanos` 호출 또는 상태 계산에 필요한 값입니다.
         * @param slotId group election backend가 slot을 식별할 때 쓰는 값입니다.
         * @param acquiringThreadId `acquiringThreadId` 호출 또는 상태 계산에 필요한 값입니다.
         * @param reentryDepth 같은 실행 컨텍스트에서 재진입한 깊이입니다.
         * @param extendDelegate `extendDelegate` 호출 또는 상태 계산에 필요한 값입니다.
         * @param auditLeaderId `auditLeaderId` 호출 또는 상태 계산에 필요한 값입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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
        ): Real = Real(identity, token, acquiredAtNanos, slotId, acquiringThreadId, reentryDepth, extendDelegate, auditLeaderId)

        /**
         * `failOpen` 호출은 leader election 계약의 일부 동작을 수행합니다.
         *
         * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
         * @param identity lock 이름, token, kind, slot 정보를 묶은 소유권 식별자입니다.
         * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
         */
        fun failOpen(identity: LockIdentity): FailOpen = FailOpen(identity)
    }
}

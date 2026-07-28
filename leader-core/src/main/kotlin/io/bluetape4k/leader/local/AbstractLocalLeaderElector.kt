package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.support.requireNotBlank
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration

/**
 * `AbstractLocalLeaderElector` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property options `options` 호출 또는 상태 계산에 필요한 값입니다.
 */
abstract class AbstractLocalLeaderElector(
    protected val options: LeaderElectionOptions = LeaderElectionOptions.Default,
) : LeaderElectionListenerRegistry, LeaderElectionState {

    companion object {
        /**
         * `LOCAL_FACTORY_BEAN_NAME`는 backend별 leader elector 인스턴스를 생성하는 factory 계약입니다.
         */
        internal const val LOCAL_FACTORY_BEAN_NAME = "local-leader-elector"
    }

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val listeners = LeaderElectionListenerSupport()
    private val states = LocalLeaderStateRegistry()

    override val supportsAuditLeaderState: Boolean = true

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    /**
     * `lockName`에 대응하는 [ReentrantLock]을 반환하고, 없으면 새로 생성합니다.
     *
     * @param lockName single leader election에 사용할 lock 이름입니다. blank 값은 허용하지 않습니다.
     * @return 지정한 lock 이름에 재사용되는 [ReentrantLock] 인스턴스입니다.
     */
    protected fun getLock(lockName: String): ReentrantLock {
        lockName.requireNotBlank("lockName")
        return locks.computeIfAbsent(lockName) { ReentrantLock() }
    }

    /**
     * `withLeaderLock` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    protected fun <T> withLeaderLock(lockName: String, action: () -> T): T {
        val lock = getLock(lockName)
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    /**
     * `tryWithLeaderLock` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param waitTime leader lock 획득을 기다리는 최대 시간입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    protected fun <T> tryWithLeaderLock(lockName: String, waitTime: Duration, action: () -> T): T? =
        tryWithLeaderLock(
            lockName = lockName,
            auditLeaderId = options.nodeId,
            nodeId = options.nodeId,
            waitTime = waitTime,
            action = action,
        )

    /**
     * `tryWithLeaderLock` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param auditLeaderId `auditLeaderId` 호출 또는 상태 계산에 필요한 값입니다.
     * @param nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
     * @param waitTime leader lock 획득을 기다리는 최대 시간입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    protected fun <T> tryWithLeaderLock(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        waitTime: Duration,
        action: () -> T,
    ): T? {
        val lock = getLock(lockName)

        // reentrant: 같은 thread가 lock을 보유한 상태이므로 passthrough handle로 감쌉니다.
        val existing = LockStateHolder.peekSyncMatching(lockName)
        if (lock.isHeldByCurrentThread && existing is LeaderLockHandle.Real) {
            val reentrant = existing.withReentryDepth(existing.reentryDepth + 1)
            return LockStateHolder.withPushed(reentrant) { action() }
        }

        val acquired = lock.tryLock(waitTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!acquired) {
            listeners.notifySkipped(lockName)
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireSingle(lockName, auditLeaderId = auditLeaderId, nodeId = nodeId, leaseTime = options.leaseTime)

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = LOCAL_FACTORY_BEAN_NAME,
        )
        val lastExtendDeadline = AtomicReference(Instant.EPOCH)
        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadline
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: Duration): io.bluetape4k.leader.ExtendOutcome {
                val extended = states.extendSingle(lockName, lockAtMostFor)
                return if (extended) {
                    io.bluetape4k.leader.ExtendOutcome.Extended(
                        Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds)
                    )
                } else {
                    io.bluetape4k.leader.ExtendOutcome.NotHeld
                }
            }
            override fun isHeld(): Boolean = states.singleState(lockName).isOccupied
        }

        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate)
        listeners.notifyElected(lockName, lease)
        return try {
            LockStateHolder.withPushed(handle) { action() }
        } finally {
            watchdog.close()
            parkRemainingMinLeaseTime(startedAtNanos, options.minLeaseTime)
            states.releaseSingle(lockName)
            lock.unlock()
            listeners.notifyRevoked(lockName)
        }
    }

    override fun state(lockName: String): LeaderState =
        states.singleState(lockName)
}

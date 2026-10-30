package io.bluetape4k.leader.local

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.tryLock
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LocalRequestLeaseStore
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.leader.parkRemainingMinLeaseTime
import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import kotlinx.atomicfu.locks.withLock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration

private val log = KotlinLogging.logger {}

/**
 * JVM 프로세스 안에서 lock별 `ReentrantLock`, 상태 snapshot, listener, request lease를 관리하는 단일 선거 기반 클래스입니다.
 * 이 구현이 제공하는 lock은 다른 프로세스와 공유되지 않습니다.
 * @property options node ID, 대기 시간, lease 및 자동 연장 설정
 */
abstract class AbstractLocalLeaderElector(
    protected val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LeaderElectionListenerRegistry,
   LeaderElectionState,
   LeaderLeaseAcquirer,
   LeaderBackendDiagnosticsProvider by LocalLeaderBackendDiagnostics {

    companion object {
        /**
         * `LOCAL_FACTORY_BEAN_NAME`는 backend별 leader elector 인스턴스를 생성하는 factory 계약입니다.
         */
        internal const val LOCAL_FACTORY_BEAN_NAME = "local-leader-elector"
    }

    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val listeners = LeaderElectionListenerSupport()
    private val states = LocalLeaderStateRegistry()
    private val requestLeases = LocalRequestLeaseStore()

    override val configuredOptions: LeaderElectionOptions
        get() = options

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
     * 해당 이름의 JVM lock을 획득할 때까지 대기한 뒤 작업을 실행하고, 작업이 끝나면 lock을 해제합니다.
     * @param lockName JVM 안에서 사용할 lock 이름
     * @param action lock을 보유한 동안 실행할 작업
     * @return `action`이 반환한 값
     */
    protected fun <T> withLeaderLock(lockName: String, action: () -> T): T {
        return getLock(lockName).withLock {
            action()
        }
    }

    /**
     * `waitTime` 안에 lock을 얻으면 작업을 실행하고 해제합니다. 시간 내에 얻지 못하면 작업을 실행하지 않고 `null`을 반환합니다.
     * @param lockName JVM 안에서 사용할 lock 이름
     * @param waitTime lock 획득을 기다릴 최대 시간
     * @param action lock을 보유한 동안 실행할 작업
     * @return lock을 얻은 경우 `action`의 결과, 시간 초과로 얻지 못한 경우 `null`
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
     * 제한 시간 안에 lock을 얻으면 상태와 listener에 지정 identity를 기록하고 작업을 실행한 뒤 lease와 lock을 정리합니다.
     * @param lockName JVM 안에서 사용할 lock 이름
     * @param auditLeaderId 상태 기록 및 listener 이벤트에 사용할 leader ID
     * @param nodeId 상태 snapshot에 기록할 노드 ID. 생략하면 `options.nodeId`를 사용합니다.
     * @param waitTime lock 획득을 기다릴 최대 시간
     * @param action lock을 보유한 동안 실행할 작업
     * @return lock을 얻은 경우 `action`의 결과, 시간 초과로 얻지 못한 경우 `null`
     */
    protected fun <T> tryWithLeaderLock(
        lockName: String,
        auditLeaderId: String,
        nodeId: String? = options.nodeId,
        waitTime: Duration,
        action: () -> T,
    ): T? {
        log.debug { "tryWithLeaderLock. lockName=$lockName, auditLeaderId=$auditLeaderId, nodeId=$nodeId" }

        val lock = getLock(lockName)

        // reentrant: 같은 thread가 lock을 보유한 상태이므로 passthrough handle로 감쌉니다.
        val existing = LockStateHolder.peekSyncMatching(lockName)
        if (lock.isHeldByCurrentThread && existing is LeaderLockHandle.Real) {
            val reentrant = existing.withReentryDepth(existing.reentryDepth + 1)
            return LockStateHolder.withPushed(reentrant) { action() }
        }

        val acquired = lock.tryLock(waitTime)
        if (!acquired) {
            listeners.notifySkipped(lockName)
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireSingle(
            lockName,
            auditLeaderId = auditLeaderId,
            nodeId = nodeId,
            leaseTime = options.leaseTime
        )

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = LOCAL_FACTORY_BEAN_NAME,
        )
        val lastExtendDeadline = AtomicReference(Instant.EPOCH)

        val delegate = object: ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadline
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: Duration): io.bluetape4k.leader.ExtendOutcome {
                val extended = states.extendSingle(lockName, lockAtMostFor)
                return if (extended) {
                    ExtendOutcome.Extended(
                        Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds)
                    )
                } else {
                    ExtendOutcome.NotHeld
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

    /** lock-name overload는 구성된 node identity를 캡처해 request lease를 획득합니다. */
    override fun tryAcquire(lockName: String): LeaderLeaseHandle? =
        tryAcquire(LeaderSlot(lockName, options.nodeId))

    /** caller slot identity를 변경하지 않고 request lease를 획득합니다. */
    override fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle? =
        requestLeases.tryAcquire(slot, options.waitTime, options)
}

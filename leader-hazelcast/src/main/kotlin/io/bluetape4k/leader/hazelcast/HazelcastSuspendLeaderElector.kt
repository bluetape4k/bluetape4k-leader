package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastSuspendLockExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * `HazelcastSuspendLeaderElector`는 Hazelcast backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property hazelcast Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class HazelcastSuspendLeaderElector private constructor(
    private val hazelcast: HazelcastInstance,
    private val options: LeaderElectionOptions,
) : SuspendLeaderElector,
    LeaderBackendDiagnosticsProvider by HazelcastLeaderBackendDiagnostics(hazelcast),
    io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter({ this }, options)
    }

    companion object: KLoggingChannel() {
        internal const val HAZELCAST_SUSPEND_FACTORY_BEAN_NAME = "hazelcast-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(HazelcastBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): HazelcastSuspendLeaderElector = HazelcastSuspendLeaderElector(hazelcast, options)
    }

    private val lockMap: IMap<String, String> = hazelcast.getMap(HazelcastLeaderElector.LOCK_MAP_NAME)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateLockName(lockName)

        val lock = HazelcastSuspendLock(
            lockMap = lockMap,
            lockKey = lockName,
            transactionMapName = HazelcastLeaderElector.LOCK_MAP_NAME,
            transactionContextProvider = hazelcast::newTransactionContext,
        )
        log.debug { "Leader 승격을 요청합니다 (suspend) ... lockName=$lockName" }

        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "Leader 승격 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        var watchdog: AutoCloseable? = null
        try {
            val delegate: SuspendExtendDelegate = HazelcastSuspendLockExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = HAZELCAST_SUSPEND_FACTORY_BEAN_NAME,
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lockName,
                acquiredAtNanos = acquiredAtNanos,
                extendDelegate = delegate,
            )
            watchdog = LeaderLeaseAutoExtender.start(
                options.autoExtend,
                options.leaseTime,
                delegate,
                ERROR_CLASSIFIER,
            )
            log.debug { "Leader로 승격하여 suspend 작업을 수행합니다. lockName=$lockName" }
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog?.close()
                try {
                    lock.unlock(options.minLeaseTime, acquiredAtNanos)
                    log.debug { "Leader 권한을 반납했습니다 (suspend). lockName=$lockName" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Fail to release lock (suspend). lockName=$lockName" }
                }
            }
        }
    }
}

/**
 * `선언` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    validateLockName(jobName)
    return HazelcastSuspendLeaderElector(this, options).runIfLeader(jobName) { action() }
}

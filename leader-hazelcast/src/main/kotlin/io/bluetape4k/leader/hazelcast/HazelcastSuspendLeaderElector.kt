package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastSuspendLockExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter
import io.bluetape4k.leader.hazelcast.suspendRunIfLeader as currentSuspendRunIfLeader
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
): SuspendLeaderElector,
   LeaderBackendDiagnosticsProvider by HazelcastLeaderBackendDiagnostics(hazelcast),
   SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer by lazy {
        SuspendLeaderElectorLeaseAdapter({ this }, options)
    }

    companion object: KLoggingChannel() {
        internal const val HAZELCAST_SUSPEND_FACTORY_BEAN_NAME = "hazelcast-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(HazelcastBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): HazelcastSuspendLeaderElector {
            return HazelcastSuspendLeaderElector(hazelcast, options)
        }
    }

    private val lockMap: IMap<String, String> = hazelcast.getMap(HazelcastLeaderElector.LOCK_MAP_NAME)

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.validateLockName()

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
                watchdog?.let { LeaderLeaseAutoExtender.closeSuspend(it) }
                try {
                    lock.unlock(options.minLeaseTime, acquiredAtNanos)
                    log.debug { "Leader 권한을 반납했습니다 (suspend). lockName=$lockName" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Leader 권한 해제에 실패했습니다 (suspend). lockName=$lockName" }
                }
            }
        }
    }
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeader")
suspend inline fun <T> HazelcastInstance.legacySuspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? = this.currentSuspendRunIfLeader(jobName, options, action)

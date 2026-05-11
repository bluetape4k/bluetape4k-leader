package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastSuspendLockExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastSuspendLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [HazelcastInstance]의 [IMap] 분산 락을 이용한 코루틴 기반 리더 선출 구현체입니다.
 *
 * 토큰 기반 락(`putIfAbsent` + TTL)으로 코루틴 스레드 전환과 무관하게 안전하게 동작합니다.
 *
 * ## ExtendDelegate 통합 (T12 PR 7 / Issue #79)
 *
 * - acquire 후 [HazelcastSuspendLockExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 동일 reference 공유 (AC-15).
 * - aspect 의 `LockExtenderSuspend.extendActiveLockSuspend` 는 동일 delegate reference 를 사용합니다.
 * - `withContext(AopScopeAccess.createLockHandleElement(handle))` 로 coroutineContext 에 handle 전파.
 * - autoExtend 옵션은 [LeaderLeaseAutoExtender] 의 watchdog 가 처리.
 *
 * ```kotlin
 * val election = HazelcastSuspendLeaderElector(hazelcastInstance)
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * **취소 안전성:** 코루틴 취소 시에도 `withContext(NonCancellable)`로 watchdog close + 락 해제를 보장합니다.
 *
 * @param hazelcast Hazelcast 클라이언트 인스턴스
 * @param options 리더 선출 옵션 (waitTime, leaseTime)
 */
class HazelcastSuspendLeaderElector private constructor(
    private val hazelcast: HazelcastInstance,
    private val options: LeaderElectionOptions,
): SuspendLeaderElector {

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
        lockName.requireNotBlank("lockName")

        val lock = HazelcastSuspendLock(lockMap, lockName)
        log.debug { "Leader 승격을 요청합니다 (suspend) ... lockName=$lockName" }

        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "Leader 승격 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        val delegate = HazelcastSuspendLockExtendDelegate(lock)
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
        val watchdog = LeaderLeaseAutoExtender.start(
            options.autoExtend,
            options.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )
        log.debug { "Leader로 승격하여 suspend 작업을 수행합니다. lockName=$lockName" }
        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                if (lock.isHeldByCurrentInstance()) {
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
}

/**
 * Hazelcast 분산 락을 이용하여 리더로 선출된 경우에만 suspend [action]을 실행합니다.
 */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.requireNotBlank("jobName")
    return HazelcastSuspendLeaderElector(this, options).runIfLeader(jobName) { action() }
}

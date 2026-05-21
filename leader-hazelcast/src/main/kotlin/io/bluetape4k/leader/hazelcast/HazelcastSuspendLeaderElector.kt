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
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Coroutine-based leader election implementation using [IMap] distributed locks from [HazelcastInstance].
 *
 * Uses a token-based lock (`putIfAbsent` + TTL) that operates safely regardless of coroutine thread switches.
 *
 * ## ExtendDelegate Integration (T12 PR 7 / Issue #79)
 *
 * - After acquire, creates [HazelcastSuspendLockExtendDelegate] sharing the same reference with
 *   [LeaderLockHandle.Real] and the watchdog (AC-15).
 * - The aspect's `LockExtenderSuspend.extendActiveLockSuspend` uses the same delegate reference.
 * - The handle is propagated to the coroutineContext via
 *   `withContext(AopScopeAccess.createLockHandleElement(handle))`.
 * - The `autoExtend` option is handled by the [LeaderLeaseAutoExtender] watchdog.
 *
 * ```kotlin
 * val election = HazelcastSuspendLeaderElector(hazelcastInstance)
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * **Cancellation Safety:** Even on coroutine cancellation, `withContext(NonCancellable)` guarantees
 * that watchdog close and lock release are always executed.
 *
 * @param hazelcast Hazelcast client instance
 * @param options Leader election options (waitTime, leaseTime)
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
 * Executes the suspend [action] only when this node is elected as leader using a Hazelcast distributed lock.
 */
suspend inline fun <T> HazelcastInstance.suspendRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: suspend () -> T,
): T? {
    jobName.requireNotBlank("jobName")
    return HazelcastSuspendLeaderElector(this, options).runIfLeader(jobName) { action() }
}

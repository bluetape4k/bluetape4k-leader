package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastLockExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.requireNotBlank
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Performs leader election using the [IMap] distributed lock of [HazelcastInstance].
 *
 * Uses a `putIfAbsent` + TTL token-based lock, so it is not bound to any thread and
 * operates safely in both Virtual Thread and ThreadPool environments.
 *
 * ## ExtendDelegate Integration (T12 PR 7 / Issue #79)
 *
 * - After acquire, creates a [HazelcastLockExtendDelegate] that shares the same reference with [LeaderLockHandle.Real] + watchdog (AC-15).
 * - When the aspect calls `LockExtender.extendActiveLock`, the extend is executed through the same delegate with R6 (IMap auto-evict blocks expired entry revival) applied.
 * - The autoExtend option is handled by the [LeaderLeaseAutoExtender] watchdog (R2 watchdog skip semantics guaranteed).
 *
 * ```kotlin
 * val election = HazelcastLeaderElector(hazelcastInstance)
 * val result = election.runIfLeader("daily-job") { processData() }
 * // result == processData() return value (leader acquired) or null (not acquired)
 * ```
 *
 * @param hazelcast Hazelcast client instance
 * @param options Leader election options (waitTime, leaseTime)
 */
class HazelcastLeaderElector private constructor(
    private val hazelcast: HazelcastInstance,
    private val options: LeaderElectionOptions,
): LeaderElector {

    companion object: KLogging() {
        const val LOCK_MAP_NAME = "bluetape4k:leader:locks"
        internal const val HAZELCAST_FACTORY_BEAN_NAME = "hazelcast-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(HazelcastBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): HazelcastLeaderElector = HazelcastLeaderElector(hazelcast, options)
    }

    private val lockMap: IMap<String, String> = hazelcast.getMap(LOCK_MAP_NAME)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = HazelcastLock(lockMap, lockName)
        log.debug { "Leader 승격을 요청합니다 ... lockName=$lockName" }

        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "Leader 승격 실패 (슬롯 없음). lockName=$lockName" }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        val delegate = HazelcastLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = HAZELCAST_FACTORY_BEAN_NAME,
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
        log.debug { "Leader로 승격하여 작업을 수행합니다. lockName=$lockName" }
        try {
            return AopScopeAccess.withPushedSync(handle) { action() }
        } finally {
            watchdog.close()
            if (lock.isHeldByCurrentInstance()) {
                runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                    .onSuccess { log.debug { "Leader 권한을 반납했습니다. lockName=$lockName" } }
                    .onFailure { e -> log.error(e) { "Fail to release lock. lockName=$lockName" } }
            }
        }
    }

    /**
     * Performs lock acquisition and action execution on the [executor] thread.
     *
     * Since the [IMap]-based token lock is not thread-bound, it is safely released on the completion callback thread.
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val lock = HazelcastLock(lockMap, lockName)

        return CompletableFuture
            .supplyAsync({ lock.tryLock(options.waitTime, options.leaseTime) }, executor)
            .thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "Leader 승격 실패 (슬롯 없음, 비동기). lockName=$lockName" }
                    CompletableFuture.completedFuture(null)
                } else {
                    val acquiredAtNanos = System.nanoTime()
                    val delegate = HazelcastLockExtendDelegate(lock)
                    val watchdog = LeaderLeaseAutoExtender.start(
                        options.autoExtend,
                        options.leaseTime,
                        delegate,
                        ERROR_CLASSIFIER,
                    )
                    log.debug { "Leader로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    // async path 는 handle push 미수행 (AOP scope sync/suspend 만 지원)
                    val actionFuture = runCatching { action() }
                        .getOrElse { error ->
                            watchdog.close()
                            runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                                .onFailure { e -> log.error(e) { "Fail to release lock on action error (async). lockName=$lockName" } }
                            return@thenComposeAsync CompletableFuture.failedFuture(error)
                        }
                    actionFuture.whenCompleteAsync({ _, _ ->
                        watchdog.close()
                        if (lock.isHeldByCurrentInstance()) {
                            runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                                .onSuccess { log.debug { "비동기 Leader 권한을 반납했습니다. lockName=$lockName" } }
                                .onFailure { e -> log.error(e) { "Fail to release lock (async). lockName=$lockName" } }
                        }
                    }, executor)
                }
            }, executor)
    }
}

/**
 * Executes [action] only when elected as leader using a Hazelcast distributed lock.
 */
inline fun <T> HazelcastInstance.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    jobName.requireNotBlank("jobName")
    return HazelcastLeaderElector(this, options).runIfLeader(jobName) { action() }
}

/**
 * Executes async [action] only when elected as leader using a Hazelcast distributed lock.
 */
inline fun <T> HazelcastInstance.runAsyncIfLeader(
    jobName: String,
    executor: Executor = java.util.concurrent.ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    jobName.requireNotBlank("jobName")
    return HazelcastLeaderElector(this, options).runAsyncIfLeader(jobName, executor) { action() }
}

package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastLockExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * `HazelcastLeaderElector`는 Hazelcast backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property hazelcast Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options Hazelcast backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class HazelcastLeaderElector private constructor(
    private val hazelcast: HazelcastInstance,
    private val options: LeaderElectionOptions,
): LeaderElector,
    LeaderBackendDiagnosticsProvider by HazelcastLeaderBackendDiagnostics(hazelcast),
    io.bluetape4k.leader.LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: io.bluetape4k.leader.LeaderLeaseAcquirer by lazy {
        io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter({ this }, options)
    }

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
        validateLockName(lockName)

        val lock = HazelcastLock(lockMap, lockName, LOCK_MAP_NAME, hazelcast::newTransactionContext)
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
            runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                .onSuccess { log.debug { "Leader 권한을 반납했습니다. lockName=$lockName" } }
                .onFailure { e -> log.error(e) { "Fail to release lock. lockName=$lockName" } }
        }
    }

    /**
     * `선언` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateLockName(lockName)

        val lock = HazelcastLock(lockMap, lockName, LOCK_MAP_NAME, hazelcast::newTransactionContext)

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
                    actionFuture.whenComplete { _, _ ->
                        watchdog.close()
                        runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                            .onSuccess { log.debug { "비동기 Leader 권한을 반납했습니다. lockName=$lockName" } }
                            .onFailure { e -> log.error(e) { "Fail to release lock (async). lockName=$lockName" } }
                    }
                }
            }, executor)
    }
}

/**
 * `선언` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> HazelcastInstance.runIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? {
    validateLockName(jobName)
    return HazelcastLeaderElector(this, options).runIfLeader(jobName) { action() }
}

/**
 * `선언` 호출은 Hazelcast backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
inline fun <T> HazelcastInstance.runAsyncIfLeader(
    jobName: String,
    executor: Executor = java.util.concurrent.ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> {
    validateLockName(jobName)
    return HazelcastLeaderElector(this, options).runAsyncIfLeader(jobName, executor) { action() }
}

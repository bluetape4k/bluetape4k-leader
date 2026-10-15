package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.AsyncLifecycle
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAcquirer
import io.bluetape4k.leader.LeaderLeaseAcquirerSupport
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastLockExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderElectorLeaseAdapter
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.hazelcast.runAsyncIfLeader as currentRunAsyncIfLeader
import io.bluetape4k.leader.hazelcast.runIfLeader as currentRunIfLeader
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.closeSafe
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * `HazelcastLeaderElector`는 Hazelcast의 blocking 단일 선출을 제공하며 lockName의 lease 획득·연장·해제를 관리합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property hazelcast 단일 lock map을 조회하고, transaction-aware lock 연산에 사용할 `HazelcastInstance`입니다.
 * @property options lock 대기 시간, lease 기간, 최소 lease 보존, auto-extend 여부를 정하는 단일 선출 정책입니다.
 */
class HazelcastLeaderElector private constructor(
    private val hazelcast: HazelcastInstance,
    private val options: LeaderElectionOptions,
): LeaderElector,
   LeaderBackendDiagnosticsProvider by HazelcastLeaderBackendDiagnostics(hazelcast),
   LeaderLeaseAcquirerSupport {

    override val leaseAcquirerDelegate: LeaderLeaseAcquirer by lazy {
        LeaderElectorLeaseAdapter({ this }, options)
    }

    companion object: KLogging() {
        const val LOCK_MAP_NAME = "bluetape4k:leader:locks"
        internal const val HAZELCAST_FACTORY_BEAN_NAME = "hazelcast-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(HazelcastBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderElectionOptions = LeaderElectionOptions.Default,
        ): HazelcastLeaderElector {
            return HazelcastLeaderElector(hazelcast, options)
        }
    }

    private val lockMap: IMap<String, String> = hazelcast.getMap(LOCK_MAP_NAME)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.validateLockName()

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
            watchdog.closeSafe()
            runCatching {
                lock.unlock(options.minLeaseTime, acquiredAtNanos)
            }.onSuccess {
                log.debug { "Leader 권한을 반납했습니다. lockName=$lockName" }
            }.onFailure {
                log.error(it) { "Leader 권한 해제를 실패했습니다. lockName=$lockName" }
            }
        }
    }

    /** `lockName` 키의 lock을 `options` 시간만큼 기다려 획득한 경우에만 동기 `action`을 실행하고, 경합이면 `null`을 반환합니다. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.validateLockName()

        val lock = HazelcastLock(lockMap, lockName, LOCK_MAP_NAME, hazelcast::newTransactionContext)

        val lockAcquired = AtomicBoolean()
        val acquiredAtNanosRef = AtomicLong()
        val lifecycle = AtomicReference(AsyncLifecycle.WAITING)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        val releaseIfUnclaimed: () -> Unit = {
            if (lockAcquired.get() && lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)) {
                runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanosRef.get()) }
                    .onSuccess {
                        log.debug { "executor 거부 후 비동기 락을 반납했습니다. lockName=$lockName" }
                    }
                    .onFailure { e ->
                        log.error(e) { "Fail to release lock after executor rejection. lockName=$lockName" }
                    }
            }
        }
        val acquisitionFuture = futureOf(executor) {
            lock.tryLock(options.waitTime, options.leaseTime)
                .also { acquired ->
                    if (acquired) {
                        acquiredAtNanosRef.set(System.nanoTime())
                        lockAcquired.set(true)
                    }
                }
        }
        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ acquired ->
                if (!acquired) {
                    log.debug { "Leader 승격 실패 (슬롯 없음, 비동기). lockName=$lockName" }
                    completableFutureOf(null)
                } else if (!lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)) {
                    failedCompletableFutureOf(CancellationException("leader result future was cancelled before action"))
                } else {
                    val acquiredAtNanos = acquiredAtNanosRef.get()
                    val delegate = HazelcastLockExtendDelegate(lock)
                    val watchdog = try {
                        LeaderLeaseAutoExtender.start(
                            options.autoExtend,
                            options.leaseTime,
                            delegate,
                            ERROR_CLASSIFIER,
                        )
                    } catch (error: Throwable) {
                        runCatching {
                            lock.unlock(options.minLeaseTime, acquiredAtNanos)
                        }.onFailure {
                            error.addSuppressed(it)
                        }
                        return@thenComposeAsync failedCompletableFutureOf(error)
                    }
                    log.debug { "Leader로 승격하여 비동기 작업을 수행합니다. lockName=$lockName" }
                    // async path 는 handle push 미수행 (AOP scope sync/suspend 만 지원)
                    val actionFuture = runCatching {
                        cancellationRelay.invoke { action() }
                    }.getOrElse { error ->
                        return@thenComposeAsync LeaderLeaseAutoExtender.closeAsync(watchdog)
                            .handle { _, closeFailure ->
                                closeFailure?.unwrapCompletionCause()?.let(error::addSuppressed)
                                runCatching { lock.unlock(options.minLeaseTime, acquiredAtNanos) }
                                    .onFailure(error::addSuppressed)
                                throw error
                            }
                    }
                    LeaderFutureBridge.flatMap(actionFuture) { value, failure ->
                        LeaderLeaseAutoExtender.closeAsync(watchdog).handle { _, closeFailure ->
                            val cleanupFailure = closeFailure?.unwrapCompletionCause()
                            val releaseFailure = runCatching {
                                lock.unlock(options.minLeaseTime, acquiredAtNanos)
                            }.onSuccess {
                                log.debug { "비동기 Leader 권한을 반납했습니다. lockName=$lockName" }
                            }.onFailure { error ->
                                log.error(error) { "Fail to release lock (async). lockName=$lockName" }
                            }.exceptionOrNull()

                            if (failure != null) {
                                cleanupFailure?.let(failure::addSuppressed)
                                releaseFailure?.let(failure::addSuppressed)
                                throw failure
                            }
                            cleanupFailure?.let { throw it }
                            releaseFailure?.let { throw it }
                            value
                        }
                    }
                }
            }, executor)
        } catch (error: Throwable) {
            acquisitionFuture.whenComplete { acquired, _ ->
                if (acquired == true) releaseIfUnclaimed()
            }
            failedCompletableFutureOf(error)
        }
        pipelineFuture.whenComplete { _, failure ->
            if (failure != null) releaseIfUnclaimed()
        }
        acquisitionFuture.whenComplete { acquired, _ ->
            if (acquired == true && pipelineFuture.isCancelled) releaseIfUnclaimed()
        }
        return LeaderFutureBridge.propagateCancellation(pipelineFuture, cancellationRelay)
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runIfLeader")
inline fun <T> HazelcastInstance.legacyRunIfLeader(
    jobName: String,
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> T,
): T? = this.currentRunIfLeader(jobName, options, action)

/** Binary compatibility shim for the pre-refactor JVM facade. */
@Deprecated("Binary compatibility shim", level = DeprecationLevel.HIDDEN)
@JvmName("runAsyncIfLeader")
inline fun <T> HazelcastInstance.legacyRunAsyncIfLeader(
    jobName: String,
    executor: Executor = java.util.concurrent.ForkJoinPool.commonPool(),
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
    crossinline action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = this.currentRunAsyncIfLeader(jobName, executor, options, action)

package io.bluetape4k.leader.hazelcast

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.map.IMap
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.AsyncLifecycle
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.hazelcast.internal.HazelcastBackendErrorClassifier
import io.bluetape4k.leader.hazelcast.internal.HazelcastSlotExtendDelegate
import io.bluetape4k.leader.hazelcast.lock.HazelcastLock
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.hazelcast.runIfLeaderGroup as currentRunIfLeaderGroup
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.support.closeSafe
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * `HazelcastLeaderGroupElector`는 Hazelcast의 blocking 그룹 선출을 제공하며 `maxLeaders` 슬롯을 acquire·extend·release합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property hazelcast 그룹 lock map을 조회하고 각 슬롯의 transaction context를 만드는 `HazelcastInstance`입니다.
 */
class HazelcastLeaderGroupElector private constructor(
    private val hazelcast: HazelcastInstance,
    options: LeaderGroupElectionOptions,
): LeaderGroupElector,
   LeaderBackendDiagnosticsProvider by HazelcastLeaderBackendDiagnostics(hazelcast) {

    companion object: KLogging() {
        const val LOCK_MAP_NAME = "bluetape4k:leader:group:locks"
        internal const val HAZELCAST_GROUP_FACTORY_BEAN_NAME = "hazelcast-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(HazelcastBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            hazelcast: HazelcastInstance,
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): HazelcastLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("maxLeaders")
            return HazelcastLeaderGroupElector(hazelcast, options)
        }
    }

    override val maxLeaders: Int = options.maxLeaders
    private val waitTime = options.waitTime
    private val leaseTime = options.leaseTime
    private val minLeaseTime = options.minLeaseTime

    private val lockMap: IMap<String, String> = hazelcast.getMap(LOCK_MAP_NAME)

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    override fun activeCount(lockName: String): Int =
        (0 until maxLeaders).count { slot -> lockMap.containsKey(slotKey(lockName, slot)) }

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        lockName.validateLockName()

        val slotWaitTime = waitTime / maxLeaders
        log.debug { "리더 그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        var acquiredLock: HazelcastLock? = null
        var acquiredSlot = -1
        var acquiredSlotKey: String? = null

        for (slot in 0 until maxLeaders) {
            val slotKeyValue = slotKey(lockName, slot)
            val lock = HazelcastLock(lockMap, slotKeyValue, LOCK_MAP_NAME, hazelcast::newTransactionContext)
            if (lock.tryLock(slotWaitTime, leaseTime)) {
                acquiredLock = lock
                acquiredSlot = slot
                acquiredSlotKey = slotKeyValue
                break
            }
        }

        if (acquiredLock == null || acquiredSlotKey == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName, slot=$acquiredSlot" }
            return null
        }

        val lock = acquiredLock
        val slot = acquiredSlot
        val slotKeyValue = acquiredSlotKey
        val acquiredAtNanos = System.nanoTime()
        log.debug { "리더 그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }

        val delegate = HazelcastSlotExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = HAZELCAST_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = slotKeyValue,
            acquiredAtNanos = acquiredAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
        )
        // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
        val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)

        try {
            return AopScopeAccess.withPushedSync(handle) {
                AopScopeAccess.setCapture(handle)
                try {
                    action()
                } finally {
                    AopScopeAccess.clearCapture()
                }
            }
        } finally {
            watchdog.closeSafe()
            runCatching {
                lock.unlock(minLeaseTime, acquiredAtNanos)
            }.onSuccess {
                log.debug { "리더 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" }
            }.onFailure {
                log.error(it) { "리더 그룹 슬롯 해제가 실패했습니다. lockName=$lockName, slot=$slot" }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.validateLockName()

        val slotWaitTime = waitTime / maxLeaders

        val acquiredRef = AtomicReference<Pair<HazelcastLock, Int>?>(null)
        val acquiredAtNanosRef = AtomicLong()
        val lifecycle = AtomicReference(AsyncLifecycle.WAITING)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()

        val releaseIfUnclaimed: () -> Unit = {
            val acquired = acquiredRef.get()
            if (acquired != null && lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)) {
                runCatching {
                    acquired.first.unlock(minLeaseTime, acquiredAtNanosRef.get())
                }.onSuccess {
                    log.debug { "executor 거부 후 비동기 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=${acquired.second}" }
                }.onFailure {
                    log.error(it) { "executor 거부 후 비동기 그룹 슬롯 해제에 실패했습니다. lockName=$lockName, slot=${acquired.second}" }
                }
            }
        }
        val acquisitionFuture = futureOf(executor) {
            (0 until maxLeaders)
                .asSequence()
                .map { slot ->
                    HazelcastLock(
                        lockMap,
                        slotKey(lockName, slot),
                        LOCK_MAP_NAME,
                        hazelcast::newTransactionContext
                    ) to slot
                }
                .firstOrNull { (lock, slot) ->
                    lock.tryLock(slotWaitTime, leaseTime).also { acquired ->
                        if (acquired) {
                            acquiredAtNanosRef.set(System.nanoTime())
                            acquiredRef.set(lock to slot)
                        }
                    }
                }
        }
        val pipelineFuture: CompletableFuture<T?> = try {
            acquisitionFuture.thenComposeAsync({ acquired ->
                if (acquired == null) {
                    log.debug { "리더 그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                    completableFutureOf(null)
                } else if (!lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)) {
                    failedCompletableFutureOf(CancellationException("leader result future was cancelled before action"))
                } else {
                    val (lock, slot) = acquired
                    val acquiredAtNanos = acquiredAtNanosRef.get()
                    log.debug { "리더 그룹 슬롯을 획득하여 비동기 작업을 수행합니다. lockName=$lockName, slot=$slot" }

                    val delegate = HazelcastSlotExtendDelegate(lock)
                    // Group elector: watchdog disabled (autoExtend 옵션 부재)
                    val watchdog = try {
                        LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)
                    } catch (error: Throwable) {
                        runCatching {
                            lock.unlock(minLeaseTime, acquiredAtNanos)
                        }.onFailure {
                            error.addSuppressed(it)
                        }
                        return@thenComposeAsync failedCompletableFutureOf(error)
                    }
                    // async path 는 handle push 미수행 (AOP scope sync/suspend 만 지원)
                    val actionFuture = runCatching {
                        cancellationRelay.invoke { action() }
                    }.getOrElse { error ->
                        return@thenComposeAsync LeaderLeaseAutoExtender.closeAsync(watchdog)
                            .handle { _, closeFailure ->
                                closeFailure?.unwrapCompletionCause()?.let(error::addSuppressed)
                                runCatching {
                                    lock.unlock(minLeaseTime, acquiredAtNanos)
                                }.onFailure(error::addSuppressed)
                                throw error
                            }
                    }
                    LeaderFutureBridge.flatMap(actionFuture) { value, failure ->
                        LeaderLeaseAutoExtender.closeAsync(watchdog).handle { _, closeFailure ->
                            val cleanupFailure = closeFailure?.unwrapCompletionCause()
                            val releaseFailure = runCatching {
                                lock.unlock(minLeaseTime, acquiredAtNanos)
                            }.onSuccess {
                                log.debug { "비동기 리더 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" }
                            }.onFailure {
                                log.error(it) { "비동기 리더 그룹 슬롯을 해제 실패 (async). lockName=$lockName, slot=$slot" }
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
                if (acquired != null) releaseIfUnclaimed()
            }
            failedCompletableFutureOf(error)
        }
        pipelineFuture.whenComplete { _, failure ->
            if (failure != null) releaseIfUnclaimed()
        }
        acquisitionFuture.whenComplete { acquired, _ ->
            if (acquired != null && pipelineFuture.isCancelled) releaseIfUnclaimed()
        }
        return LeaderFutureBridge.propagateCancellation(pipelineFuture, cancellationRelay)
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("runIfLeaderGroup")
inline fun <T> HazelcastInstance.legacyRunIfLeaderGroup(
    lockName: String,
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
    crossinline action: () -> T,
): T? = this.currentRunIfLeaderGroup(lockName, options, action)

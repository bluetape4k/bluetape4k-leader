package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoSlotExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import org.bson.Document
import java.time.Instant
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration

/**
 * `MongoLeaderGroupElector`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property groupCollection MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property options MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property historyRecorder MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
@Suppress("TooManyFunctions")
class MongoLeaderGroupElector private constructor(
    private val groupCollection: MongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
    /**
     * `historyRecorder` 값은 MongoDB backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    private val historyRecorder: SafeLeaderHistoryRecorder? = null,
) : LeaderGroupElector, LeaderBackendDiagnosticsProvider by MongoLeaderBackendDiagnostics {

    companion object : KLogging() {
        internal const val MONGO_GROUP_FACTORY_BEAN_NAME = "mongo-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        @JvmStatic
        operator fun invoke(
            groupCollection: MongoCollection<Document>,
            options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
            historyRecorder: SafeLeaderHistoryRecorder? = null,
        ): MongoLeaderGroupElector {
            MongoLock.ensureIndexes(groupCollection)
            return MongoLeaderGroupElector(groupCollection, options, historyRecorder)
        }
    }

    override val maxLeaders: Int get() = options.maxLeaders

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    /**
     * `activeCount` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun activeCount(lockName: String): Int {
        val ids = (0 until maxLeaders).map { slotKey(lockName, it) }
        return groupCollection.countDocuments(
            Filters.and(
                Filters.`in`("_id", ids),
                Filters.gt("expireAt", Date())
            )
        ).toInt()
    }

    override fun availableSlots(lockName: String): Int = maxLeaders - activeCount(lockName)

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        validateMongoLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)

        log.debug { "리더 그룹 슬롯 획득을 요청합니다. lockName=$lockName, maxLeaders=$maxLeaders" }

        for (i in 0 until maxLeaders) {
            val slot = (start + i) % maxLeaders
            val slotKeyValue = slotKey(lockName, slot)
            val lock = MongoLock(groupCollection, slotKeyValue, options.retryDelay)

            if (!lock.tryLock(perSlotWait, leaseTime)) continue

            val startedAt = Instant.now()
            val acquiredAtNanos = System.nanoTime()
            log.debug { "리더 그룹 슬롯을 획득하여 작업을 수행합니다. lockName=$lockName, slot=$slot" }
            val historyKey = recordAcquired(lockName, lock.token, slot, startedAt, leaseTime)

            val delegate = MongoSlotExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.GROUP,
                factoryBeanName = MONGO_GROUP_FACTORY_BEAN_NAME,
                groupParams = LockIdentity.GroupParams(maxLeaders),
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = lock.token,
                acquiredAtNanos = acquiredAtNanos,
                slotId = slot.toString(),
                extendDelegate = delegate,
            )
            // Group elector: autoExtend 옵션 부재 — caller 가 LockExtender 로 명시적 연장. watchdog disabled.
            val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)
            var actionSucceeded = false
            var capturedError: Throwable? = null

            try {
                val result = AopScopeAccess.withPushedSync(handle) {
                    AopScopeAccess.setCapture(handle)
                    try {
                        action()
                    } finally {
                        AopScopeAccess.clearCapture()
                    }
                }
                actionSucceeded = true
                return result
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                capturedError = e
                throw e
            } catch (e: Throwable) {
                capturedError = e
                throw e
            } finally {
                watchdog.close()
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                when {
                    actionSucceeded -> recordCompleted(historyKey, finishedAt, durationMs)
                    capturedError != null -> recordFailed(historyKey, finishedAt, durationMs, capturedError)
                }
                runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                    .onSuccess { log.debug { "리더 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                    .onFailure { e -> log.warn(e) { "그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
            }
        }

        log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음). lockName=$lockName" }
        return null
    }

    @Suppress("TooGenericExceptionCaught")
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateMongoLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()

        val rejectionCleanup = AsyncSlotRejectionCleanup(lockName)
        val acquisitionFuture = acquireSlotAsync(lockName, start, perSlotWait, leaseTime)
        acquisitionFuture.whenComplete { acquired, _ ->
            if (acquired != null) rejectionCleanup.markAcquired(acquired)
        }
        val pipelineFuture = acquisitionFuture.thenComposeAsync({ acquired ->
            if (acquired == null) {
                log.debug { "리더 그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val (lock, slot) = acquired
                rejectionCleanup.markAcquired(acquired)
                val acquiredAtNanos = rejectionCleanup.acquiredAtNanos
                if (!rejectionCleanup.markLifecycleStarted()) {
                    CompletableFuture.failedFuture(
                        CancellationException("leader group action was cancelled before start"),
                    )
                } else try {
                    runAcquiredAsync(lock, lockName, slot, acquiredAtNanos, leaseTime, cancellationRelay, action)
                } catch (error: Throwable) {
                    releaseAcquiredSlot(lock, lockName, slot, acquiredAtNanos, error)
                }
            }
        }, executor)
        pipelineFuture.whenComplete { _, _ ->
            if (pipelineFuture.isCancelled) {
                acquisitionFuture.cancel(true)
                rejectionCleanup.release<Any?>(
                    CancellationException("leader group result future was cancelled before action"),
                )
            }
        }
        return LeaderFutureBridge.propagateCancellation(
            LeaderFutureBridge.flatMap(pipelineFuture) { value, failure ->
                if (failure != null) {
                    rejectionCleanup.release(failure.unwrapCompletionCause())
                } else {
                    CompletableFuture.completedFuture(value)
                }
            },
            cancellationRelay,
        )
    }

    private fun <T> runAcquiredAsync(
        lock: MongoLock,
        lockName: String,
        slot: Int,
        acquiredAtNanos: Long,
        leaseTime: Duration,
        cancellationRelay: LeaderFutureBridge.CancellationRelay,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val startedAt = Instant.now()
        log.debug { "리더 그룹 슬롯을 획득하여 비동기 작업을 수행합니다. lockName=$lockName, slot=$slot" }
        val delegate = MongoSlotExtendDelegate(lock)
        val historyKey = recordAcquired(lockName, lock.token, slot, startedAt, leaseTime)
        val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)
        val actionFuture = runCatching { cancellationRelay.invoke(action) }.getOrElse { error ->
            val finishedAt = Instant.now()
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
            recordFailed(historyKey, finishedAt, durationMs, error)
            watchdog.close()
            return releaseAcquiredSlot(lock, lockName, slot, acquiredAtNanos, error)
        }
        return AsyncLeaseCleanupDispatcher.completeAfter(
            source = actionFuture,
            cleanup = { watchdog.close() },
        ) { value, failure ->
            val finishedAt = Instant.now()
            val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
            val cause = failure?.unwrapCompletionCause()
            try {
                when {
                    cause == null -> recordCompleted(historyKey, finishedAt, durationMs)
                    cause is CancellationException -> Unit
                    else -> recordFailed(historyKey, finishedAt, durationMs, cause)
                }
            } finally {
                runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                    .onSuccess { log.debug { "비동기 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                    .onFailure { error ->
                        log.warn(error) { "비동기 그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" }
                    }
            }
            if (cause != null) throw cause
            value
        }
    }

    private fun <T> releaseAcquiredSlot(
        lock: MongoLock,
        lockName: String,
        slot: Int,
        acquiredAtNanos: Long,
        failure: Throwable,
    ): CompletableFuture<T?> {
        return AsyncLeaseCleanupDispatcher.failAfter(failure) {
            runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                .onSuccess { log.debug { "비동기 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                .onFailure { error ->
                    failure.addSuppressed(error)
                    log.warn(error) { "비동기 그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" }
                }
        }
    }

    private inner class AsyncSlotRejectionCleanup(
        private val lockName: String,
    ) {
        private val acquired = AtomicReference<Pair<MongoLock, Int>?>()
        private val cleanupStarted = AtomicBoolean()
        private val acquiredAtNanosRef = AtomicLong()
        private val lifecycle = AtomicReference(AsyncLifecycle.WAITING)

        val acquiredAtNanos: Long get() = acquiredAtNanosRef.get()

        fun markAcquired(acquiredSlot: Pair<MongoLock, Int>) {
            if (acquired.compareAndSet(null, acquiredSlot)) {
                acquiredAtNanosRef.set(System.nanoTime())
                if (lifecycle.get() == AsyncLifecycle.CLEANUP && cleanupStarted.compareAndSet(false, true)) {
                    scheduleLateCleanup(acquiredSlot)
                }
            }
        }

        fun markLifecycleStarted(): Boolean =
            lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.STARTED)

        fun <T> release(failure: Throwable): CompletableFuture<T?> {
            if (!lifecycle.compareAndSet(AsyncLifecycle.WAITING, AsyncLifecycle.CLEANUP)) {
                return CompletableFuture.failedFuture(failure)
            }
            val acquiredSlot = acquired.get()
            return if (acquiredSlot != null && cleanupStarted.compareAndSet(false, true)) {
                val (lock, slot) = acquiredSlot
                releaseAcquiredSlot(lock, lockName, slot, acquiredAtNanos, failure)
            } else {
                CompletableFuture.failedFuture(failure)
            }
        }

        private fun scheduleLateCleanup(acquiredSlot: Pair<MongoLock, Int>) {
            val (lock, slot) = acquiredSlot
            AsyncLeaseCleanupDispatcher.execute {
                runCatching { lock.unlock() }
                    .onFailure { error ->
                        log.warn(error) { "비동기 그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" }
                    }
            }
        }
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        if (this is CompletionException) cause ?: this else this

    private enum class AsyncLifecycle {
        WAITING,
        STARTED,
        CLEANUP,
    }

    @Suppress("CyclomaticComplexMethod")
    private fun acquireSlotAsync(
        lockName: String,
        start: Int,
        perSlotWait: Duration,
        leaseTime: Duration,
    ): CompletableFuture<Pair<MongoLock, Int>?> {
        val currentStage = AtomicReference<CompletableFuture<*>?>()
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        val cancellationTarget = object : CompletableFuture<Unit>() {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                currentStage.get()?.cancel(mayInterruptIfRunning)
                return super.cancel(mayInterruptIfRunning)
            }
        }
        cancellationRelay.invoke { cancellationTarget }
        val resultSource = CompletableFuture<Pair<MongoLock, Int>?>()
        val result = LeaderFutureBridge.propagateCancellation(resultSource, cancellationRelay)

        fun complete(value: Pair<MongoLock, Int>?): Boolean {
            val completed = result.complete(value)
            resultSource.complete(value)
            return completed
        }

        fun completeExceptionally(failure: Throwable): Boolean {
            val completed = result.completeExceptionally(failure)
            resultSource.completeExceptionally(failure)
            return completed
        }

        fun registerStage(stage: CompletableFuture<*>): Boolean {
            currentStage.set(stage)
            if (result.isDone && currentStage.compareAndSet(stage, null)) {
                stage.cancel(true)
                return false
            }
            return true
        }

        fun releaseLateAcquisition(lock: MongoLock, released: AtomicBoolean) {
            if (released.compareAndSet(false, true)) {
                AsyncLeaseCleanupDispatcher.execute {
                    runCatching { lock.unlock() }
                        .onFailure { error ->
                            log.warn(error) { "취소된 async 그룹 슬롯 획득을 반납하지 못했습니다." }
                        }
                }
            }
        }

        fun attempt(offset: Int): CompletableFuture<Pair<MongoLock, Int>?> {
            if (result.isDone) {
                return result
            }

            val slot = (start + offset) % maxLeaders
            val lock = MongoLock(groupCollection, slotKey(lockName, slot), options.retryDelay)
            val lateRelease = AtomicBoolean()
            val acquisition = lock.tryLockAsync(perSlotWait, leaseTime)
            registerStage(acquisition)
            acquisition.whenComplete { acquired, failure ->
                currentStage.compareAndSet(acquisition, null)
                if (failure != null) {
                    if (!result.isDone) completeExceptionally(failure)
                    return@whenComplete
                }
                if (result.isCancelled) {
                    if (acquired) releaseLateAcquisition(lock, lateRelease)
                    return@whenComplete
                }
                if (acquired) {
                    if (!complete(lock to slot)) releaseLateAcquisition(lock, lateRelease)
                } else if (offset + 1 >= maxLeaders) {
                    complete(null)
                } else {
                    attempt(offset + 1)
                }
            }
            return result
        }

        attempt(0)
        return result
    }

    private fun recordAcquired(
        lockName: String,
        token: String,
        slot: Int,
        acquiredAt: Instant,
        leaseTime: Duration,
    ): LeaderHistoryKey? {
        val record = historyRecorder?.let {
            LeaderLockHistoryRecord(
                lockName = lockName,
                token = token,
                kind = LockIdentity.AnnotationKind.GROUP,
                acquiredAt = acquiredAt,
                lockedUntil = acquiredAt.plusMillis(leaseTime.inWholeMilliseconds),
                slotId = slot.toString(),
            )
        }
        return record?.let { historyRecorder.recordAcquired(it) }
            ?: record?.let { LeaderHistoryKey(lockName = lockName, token = token, slotId = slot.toString()) }
    }

    private fun recordCompleted(historyKey: LeaderHistoryKey?, finishedAt: Instant, durationMs: Long) =
        historyKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }

    private fun recordFailed(historyKey: LeaderHistoryKey?, finishedAt: Instant, durationMs: Long, error: Throwable?) =
        historyKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, error) }
}

/**
 * `선언` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> MongoCollection<Document>.runIfLeaderGroup(
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: () -> T,
): T? = MongoLeaderGroupElector(this, options).runIfLeader(lockName, action)

/**
 * `선언` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun <T> MongoCollection<Document>.runAsyncIfLeaderGroup(
    lockName: String,
    executor: Executor = VirtualThreadExecutor,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: () -> CompletableFuture<T>,
): CompletableFuture<T?> = MongoLeaderGroupElector(this, options).runAsyncIfLeader(lockName, executor, action)

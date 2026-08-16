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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
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

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        validateMongoLockName(lockName)

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)

        return acquireSlotAsync(lockName, start, perSlotWait, leaseTime).thenComposeAsync({ acquired ->
            if (acquired == null) {
                log.debug { "리더 그룹 슬롯 획득 실패 (비동기). lockName=$lockName" }
                CompletableFuture.completedFuture(null)
            } else {
                val (lock, slot) = acquired
                val acquiredAtNanos = System.nanoTime()
                val startedAt = Instant.now()
                log.debug { "리더 그룹 슬롯을 획득하여 비동기 작업을 수행합니다. lockName=$lockName, slot=$slot" }
                val delegate = MongoSlotExtendDelegate(lock)
                val historyKey = recordAcquired(lockName, lock.token, slot, startedAt, leaseTime)
                // Group elector: watchdog disabled (autoExtend 옵션 부재)
                val watchdog = LeaderLeaseAutoExtender.start(false, leaseTime, delegate, ERROR_CLASSIFIER)
                // async path 는 handle push 미수행 (AOP scope sync/suspend 만 지원)
                val actionFuture = runCatching { action() }
                    .getOrElse { e ->
                        val finishedAt = Instant.now()
                        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                        recordFailed(historyKey, finishedAt, durationMs, e)
                        watchdog.close()
                        runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                            .onFailure { ex -> log.warn(ex) { "그룹 슬롯 해제 실패 (action 오류 경로). lockName=$lockName, slot=$slot" } }
                        return@thenComposeAsync CompletableFuture.failedFuture(e)
                    }
                actionFuture.whenComplete { _, throwable ->
                    val finishedAt = Instant.now()
                    val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                    if (throwable == null) {
                        recordCompleted(historyKey, finishedAt, durationMs)
                    } else {
                        recordFailed(historyKey, finishedAt, durationMs, throwable)
                    }
                    watchdog.close()
                    runCatching { lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos) }
                        .onSuccess { log.debug { "비동기 그룹 슬롯을 반납했습니다. lockName=$lockName, slot=$slot" } }
                        .onFailure { e -> log.warn(e) { "비동기 그룹 슬롯 해제 실패. lockName=$lockName, slot=$slot" } }
                }
            }
        }, executor)
    }

    private fun acquireSlotAsync(
        lockName: String,
        start: Int,
        perSlotWait: Duration,
        leaseTime: Duration,
    ): CompletableFuture<Pair<MongoLock, Int>?> {
        fun attempt(offset: Int): CompletableFuture<Pair<MongoLock, Int>?> {
            if (offset >= maxLeaders) {
                return CompletableFuture.completedFuture(null)
            }

            val slot = (start + offset) % maxLeaders
            val lock = MongoLock(groupCollection, slotKey(lockName, slot), options.retryDelay)
            return lock.tryLockAsync(perSlotWait, leaseTime)
                .thenCompose { acquired ->
                    if (acquired) {
                        CompletableFuture.completedFuture(lock to slot)
                    } else {
                        attempt(offset + 1)
                    }
                }
        }

        return attempt(0)
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

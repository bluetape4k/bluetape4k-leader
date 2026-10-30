package io.bluetape4k.leader.mongodb

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoSuspendSlotExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bson.Document
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import com.mongodb.kotlin.client.coroutine.MongoCollection as CoroutineMongoCollection
import io.bluetape4k.leader.mongodb.suspendRunIfLeaderGroup as currentSuspendRunIfLeaderGroup

/**
 * `MongoSuspendLeaderGroupElector`는 MongoDB의 suspend 그룹 선출을 제공하며 `maxLeaders` 슬롯을 acquire·extend·release합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property groupCollection live slot 수를 동기적으로 조회하고 같은 namespace의 lock 문서를 다루는 MongoDB collection입니다.
 * @property coroutineGroupCollection slot 획득·해제를 suspend API로 수행하는 동일 namespace의 coroutine collection입니다.
 * @property options `maxLeaders`와 core 대기·lease·최소 lease 정책, MongoDB 재시도 지연 상한을 담는 그룹 옵션입니다.
 * @property historyRecorder 슬롯 획득·완료·실패 이력을 best-effort로 기록할 선택적 suspend recorder입니다. `null`이면 기록하지 않습니다.
 */
class MongoSuspendLeaderGroupElector private constructor(
    private val groupCollection: MongoCollection<Document>,
    private val coroutineGroupCollection: CoroutineMongoCollection<Document>,
    val options: MongoLeaderGroupElectionOptions,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
): SuspendLeaderGroupElector, LeaderBackendDiagnosticsProvider by MongoLeaderBackendDiagnostics {

    init {
        check(groupCollection.namespace.fullName == coroutineGroupCollection.namespace.fullName) {
            "groupCollection과 coroutineGroupCollection은 동일한 namespace여야 합니다: " +
                    "${groupCollection.namespace.fullName} vs ${coroutineGroupCollection.namespace.fullName}"
        }
    }

    companion object: KLoggingChannel() {
        internal const val MONGO_SUSPEND_GROUP_FACTORY_BEAN_NAME = "mongo-suspend-leader-group-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        suspend operator fun invoke(
            groupCollection: MongoCollection<Document>,
            coroutineGroupCollection: CoroutineMongoCollection<Document>,
            options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
            historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
        ): MongoSuspendLeaderGroupElector {
            // 두 컬렉션은 동일 namespace이므로 coroutine 드라이버에서 한 번만 인덱스 생성하면 충분
            MongoSuspendLock.ensureIndexes(coroutineGroupCollection)
            return MongoSuspendLeaderGroupElector(groupCollection, coroutineGroupCollection, options, historyRecorder)
        }
    }

    override val maxLeaders: Int get() = options.maxLeaders

    private fun slotKey(lockName: String, slot: Int) = "$lockName:slot:$slot"

    /** `0 until maxLeaders` 슬롯 중 `_id`가 존재하고 `expireAt`이 현재보다 큰 live 문서 수를 반환합니다. */
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

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.validateMongoLockName()

        val leaseTime = options.leaderGroupOptions.leaseTime
        val perSlotWait = options.leaderGroupOptions.waitTime / maxLeaders
        val start = Random.nextInt(maxLeaders)

        log.debug { "리더 그룹 슬롯 획득을 요청합니다 (suspend). lockName=$lockName, maxLeaders=$maxLeaders" }

        var acquiredLock: MongoSuspendLock? = null
        var acquiredSlot = -1
        var acquiredSlotKey: String? = null

        for (i in 0 until maxLeaders) {
            currentCoroutineContext().ensureActive()
            val slot = (start + i) % maxLeaders
            val slotKeyValue = slotKey(lockName, slot)
            val lock = MongoSuspendLock(coroutineGroupCollection, slotKeyValue, options.retryDelay)

            if (lock.tryLock(perSlotWait, leaseTime)) {
                acquiredLock = lock
                acquiredSlot = slot
                acquiredSlotKey = slotKeyValue
                break
            }
        }

        if (acquiredLock == null || acquiredSlotKey == null) {
            log.debug { "리더 그룹 슬롯 획득 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        log.debug { "리더 그룹 슬롯을 획득하여 suspend 작업을 수행합니다. lockName=$lockName, slot=$acquiredSlot" }
        val lock = acquiredLock
        val slot = acquiredSlot
        val startedAt = Instant.now()
        val acquiredAtNanos = System.nanoTime()
        val historyKey = recordAcquired(lockName, lock.token, slot, startedAt, leaseTime)

        val delegate: SuspendExtendDelegate = MongoSuspendSlotExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = MONGO_SUSPEND_GROUP_FACTORY_BEAN_NAME,
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
            val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
            actionSucceeded = true
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            capturedError = e
            throw e
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + release 가 중단되지 않도록 보호
            withContext(NonCancellable) {
                LeaderLeaseAutoExtender.closeSuspend(watchdog)
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                when {
                    actionSucceeded -> recordCompleted(historyKey, finishedAt, durationMs)
                    capturedError != null -> recordFailed(historyKey, finishedAt, durationMs, capturedError)
                }
                try {
                    lock.unlock(options.leaderGroupOptions.minLeaseTime, acquiredAtNanos)
                    log.debug { "리더 그룹 슬롯을 반납했습니다 (suspend). lockName=$lockName, slot=$slot" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "그룹 슬롯 해제 실패 (suspend). lockName=$lockName, slot=$slot" }
                }
            }
        }
    }

    private suspend fun recordAcquired(
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

    private suspend fun recordCompleted(historyKey: LeaderHistoryKey?, finishedAt: Instant, durationMs: Long) =
        historyKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }

    private suspend fun recordFailed(
        historyKey: LeaderHistoryKey?,
        finishedAt: Instant,
        durationMs: Long,
        error: Throwable?,
    ) =
        historyKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, error) }
}

/** 리팩터링 전 JVM facade의 바이너리 호환성을 보존하는 shim입니다. */
@Deprecated("리팩터링 전 JVM facade 호환성 유지용", level = DeprecationLevel.HIDDEN)
@JvmName("suspendRunIfLeaderGroup")
suspend fun <T> MongoCollection<Document>.legacySuspendRunIfLeaderGroup(
    coroutineGroupCollection: CoroutineMongoCollection<Document>,
    lockName: String,
    options: MongoLeaderGroupElectionOptions = MongoLeaderGroupElectionOptions.Default,
    action: suspend () -> T,
): T? = this.currentSuspendRunIfLeaderGroup(coroutineGroupCollection, lockName, options, action)

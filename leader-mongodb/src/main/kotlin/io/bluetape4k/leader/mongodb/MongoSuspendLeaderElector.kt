package io.bluetape4k.leader.mongodb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoBackendErrorClassifier
import io.bluetape4k.leader.mongodb.internal.MongoSuspendLockExtendDelegate
import io.bluetape4k.leader.mongodb.lock.MongoSuspendLock
import io.bluetape4k.leader.mongodb.lock.validateMongoLockName
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.bson.Document

/**
 * MongoDB 분산 락을 이용한 코루틴 기반 단일 리더 선출 구현체입니다.
 *
 * 토큰 기반 락 (`findOneAndUpdate` + TTL)으로 코루틴 스레드 전환과 무관하게 안전합니다.
 *
 * ## ExtendDelegate 통합 (T9 PR 4 / Issue #79)
 *
 * - acquire 후 [MongoSuspendLockExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 동일 reference 공유 (AC-15).
 * - aspect 의 `LockExtenderSuspend.extendActiveLockSuspend` 는 동일 delegate reference 를 사용합니다.
 * - `withContext(AopScopeAccess.createLockHandleElement(handle))` 로 coroutineContext 에 handle 전파.
 * - autoExtend 옵션은 [LeaderLeaseAutoExtender] 의 watchdog 가 처리 — 별도 `launch` watchdog 제거.
 *
 * ```kotlin
 * val election = MongoSuspendLeaderElector(coroutineDatabase.getCollection("bluetape4k_leader_locks"))
 * val result = election.runIfLeader("daily-job") {
 *     delay(100)
 *     processData()
 * }
 * ```
 *
 * **취소 안전성:** 코루틴 취소 시에도 `withContext(NonCancellable)`로 watchdog close + 락 해제를 보장합니다.
 *
 * @param collection 락 상태를 저장하는 코루틴 [MongoCollection]
 * @param options 리더 선출 옵션
 */
class MongoSuspendLeaderElector private constructor(
    private val collection: MongoCollection<Document>,
    val options: MongoLeaderElectionOptions,
) : SuspendLeaderElector {

    companion object : KLoggingChannel() {
        internal const val MONGO_SUSPEND_FACTORY_BEAN_NAME = "mongo-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(MongoBackendErrorClassifier)

        suspend operator fun invoke(
            collection: MongoCollection<Document>,
            options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
        ): MongoSuspendLeaderElector {
            MongoSuspendLock.ensureIndexes(collection)
            return MongoSuspendLeaderElector(collection, options)
        }
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        validateMongoLockName(lockName)
        val lock = MongoSuspendLock(collection, lockName, options.retryDelay)
        log.debug { "리더 승격을 요청합니다 (suspend). lockName=$lockName" }

        if (!lock.tryLock(options.leaderOptions.waitTime, options.leaderOptions.leaseTime)) {
            log.debug { "리더 승격 실패 (슬롯 없음, suspend). lockName=$lockName" }
            return null
        }

        val acquiredAtNanos = System.nanoTime()
        val delegate = MongoSuspendLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = MONGO_SUSPEND_FACTORY_BEAN_NAME,
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = lockName,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
        )
        val watchdog = LeaderLeaseAutoExtender.start(
            options.leaderOptions.autoExtend,
            options.leaderOptions.leaseTime,
            delegate,
            ERROR_CLASSIFIER,
        )
        log.debug { "리더로 승격하여 suspend 작업을 수행합니다. lockName=$lockName" }
        try {
            return withContext(AopScopeAccess.createLockHandleElement(handle)) {
                action()
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 watchdog close + 락 해제가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog.close()
                try {
                    lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)
                    log.debug { "리더 권한을 반납했습니다 (suspend). lockName=$lockName" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "락 해제 실패 (suspend). lockName=$lockName" }
                }
            }
        }
    }
}

/**
 * MongoDB 분산 락을 이용하여 리더로 선출된 경우에만 suspend [action]을 실행합니다.
 */
suspend fun <T> MongoCollection<Document>.suspendRunIfLeader(
    lockName: String,
    options: MongoLeaderElectionOptions = MongoLeaderElectionOptions.Default,
    action: suspend () -> T,
): T? = MongoSuspendLeaderElector(this, options).runIfLeader(lockName, action)

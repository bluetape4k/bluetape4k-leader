package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceLockExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * [StatefulRedisConnection]에서 [LettuceLeaderElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val election = connection.leaderElection()
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param options 리더 선출 옵션 (기본값: [LeaderElectionOptions.Default])
 * @return [LettuceLeaderElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.leaderElection(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceLeaderElector = LettuceLeaderElector(this, options)


/**
 * Lettuce Redis 클라이언트를 이용한 리더 선출 구현체입니다.
 *
 * [LettuceLock]을 사용하여 분산 환경에서 단일 리더를 선출합니다.
 * 동기([runIfLeader])와 비동기([runAsyncIfLeader]) 방식을 모두 지원합니다.
 *
 * ## 동작/계약 (T7 PR 2)
 *
 * - acquire 후 [LettuceLockExtendDelegate] 를 생성하여 [LeaderLockHandle.Real] + watchdog 와 공유합니다.
 * - aspect 가 `LockAssert` / `LockExtender` 로 lease 연장 시 동일 delegate reference 를 사용합니다 (AC-15).
 * - watchdog 의 [LeaderLeaseAutoExtender.start] 도 동일 delegate 를 받아 R2 watchdog skip semantics 를 활성화합니다.
 *
 * ```kotlin
 * val election = LettuceLeaderElector(connection)
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (waitTime, leaseTime)
 */
class LettuceLeaderElector(
    private val connection: StatefulRedisConnection<String, String>,
    private val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LeaderElector {

    companion object: KLogging() {
        internal const val LETTUCE_FACTORY_BEAN_NAME = "lettuce-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
    }

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val value = runImpl(slot.lockName, auditLeaderId = slot.leaderId) { elected = true; action() }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private fun <T> runImpl(lockName: String, auditLeaderId: String?, action: () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = LettuceLock(connection, lockName, options.leaseTime)
        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "리더 선출 실패 (슬롯 없음): lockName=$lockName" }
            return null
        }
        val acquiredAtNanos = System.nanoTime()
        val token = lock.currentToken() ?: error("token missing after tryLock — lockName=$lockName")
        val delegate = LettuceLockExtendDelegate(lock)
        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.SINGLE,
            factoryBeanName = LETTUCE_FACTORY_BEAN_NAME,
        )
        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = acquiredAtNanos,
            extendDelegate = delegate,
            auditLeaderId = auditLeaderId,
        )
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate, ERROR_CLASSIFIER)
        log.debug { "리더 선출 성공: lockName=$lockName" }
        try {
            return AopScopeAccess.withPushedSync(handle) { action() }
        } finally {
            watchdog.close()
            runCatching {
                if (lock.isHeldByCurrentInstance()) {
                    lock.unlock(options.minLeaseTime, acquiredAtNanos)
                }
            }.onFailure { log.warn(it) { "Fail to release lock. lockName=$lockName" } }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        lockName.requireNotBlank("lockName")

        val lock = LettuceLock(connection, lockName, options.leaseTime)
        return CompletableFuture.supplyAsync({
            val acquired = lock.tryLock(options.waitTime, options.leaseTime)
            if (!acquired) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
            }
            acquired
        }, executor).thenCompose { acquired ->
            if (!acquired) {
                CompletableFuture.completedFuture(null)
            } else {
                val acquiredAtNanos = System.nanoTime()
                val delegate = LettuceLockExtendDelegate(lock)
                val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate, ERROR_CLASSIFIER)
                log.debug { "리더 선출 성공 (async): lockName=$lockName" }
                try {
                    action().whenComplete { _, _ ->
                        watchdog.close()
                        runCatching {
                            if (lock.isHeldByCurrentInstance()) {
                                lock.unlock(options.minLeaseTime, acquiredAtNanos)
                            }
                        }.onFailure { log.warn(it) { "Fail to release lock. lockName=$lockName" } }
                    }
                } catch (e: Throwable) {
                    watchdog.close()
                    runCatching {
                        if (lock.isHeldByCurrentInstance()) {
                            lock.unlock(options.minLeaseTime, acquiredAtNanos)
                        }
                    }.onFailure { log.warn(it) { "Fail to release lock. lockName=$lockName" } }
                    CompletableFuture.failedFuture(e)
                }
            }
        }
    }
}


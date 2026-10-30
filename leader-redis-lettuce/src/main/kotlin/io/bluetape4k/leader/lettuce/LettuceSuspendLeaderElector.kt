package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.AopScopeAccess
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.history.LeaderHistoryKey
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.history.SuspendSafeLeaderHistoryRecorder
import io.bluetape4k.leader.internal.CompositeBackendErrorClassifier
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.leader.internal.SuspendLeaderElectorLeaseAdapter
import io.bluetape4k.leader.lettuce.internal.LettuceBackendErrorClassifier
import io.bluetape4k.leader.lettuce.internal.LettuceSuspendLockExtendDelegate
import io.bluetape4k.leader.lettuce.lock.LettuceSuspendLock
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/** 주어진 Redis 연결과 단일 선출 정책으로 suspend `LettuceSuspendLeaderElector`를 생성합니다. */
fun StatefulRedisConnection<String, String>.suspendLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceSuspendLeaderElector =
    LettuceSuspendLeaderElector(this, options)


/**
 * `LettuceSuspendLeaderElector`는 Redis Lettuce의 suspend 단일 선출을 제공하며 lockName의 lease 획득·연장·해제를 관리합니다.
 *
 * lock 또는 slot을 획득하지 못한 `runIfLeader` 호출은 예외 대신 `null`/skip 결과를 반환합니다.
 * @property connection suspend lock key의 token을 저장·갱신·삭제하는 Redis 연결입니다.
 * @property options lock 대기·lease·최소 lease·auto-extend 동작을 정하는 단일 선출 정책입니다.
 * @property historyRecorder 획득·완료·실패 이력을 best-effort로 전달할 선택적 suspend recorder입니다. `null`이면 이력을 기록하지 않습니다.
 */
class LettuceSuspendLeaderElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderElectionOptions = LeaderElectionOptions.Default,
    private val historyRecorder: SuspendSafeLeaderHistoryRecorder? = null,
): SuspendLeaderElector,
   LeaderBackendDiagnosticsProvider by LettuceLeaderBackendDiagnostics(connection),
   SuspendLeaderLeaseAcquirerSupport {

    override val suspendLeaseAcquirerDelegate: SuspendLeaderLeaseAcquirer by lazy {
        SuspendLeaderElectorLeaseAdapter({ this }, options)
    }

    companion object: KLogging() {
        internal const val LETTUCE_SUSPEND_FACTORY_BEAN_NAME = "lettuce-suspend-leader-elector"
        internal val ERROR_CLASSIFIER = CompositeBackendErrorClassifier(LettuceBackendErrorClassifier)
    }

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? =
        runImpl(lockName, auditLeaderId = null, action)

    override suspend fun <T> runIfLeader(slot: LeaderSlot, action: suspend () -> T): T? =
        runImpl(slot.lockName, auditLeaderId = slot.leaderId, action)

    override suspend fun <T> runIfLeaderResultSuspend(slot: LeaderSlot, action: suspend () -> T): LeaderRunResult<T> {
        var elected = false
        val value = try {
            runImpl(slot.lockName, auditLeaderId = slot.leaderId) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    private suspend fun <T> runImpl(lockName: String, auditLeaderId: String?, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = LettuceSuspendLock(connection, lockName, options.leaseTime)
        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "리더 선출 실패 (슬롯 없음, suspend): lockName=$lockName" }
            return null
        }
        val acquiredAtNanos = System.nanoTime()
        val token = lock.currentToken() ?: error("token missing after tryLock — lockName=$lockName")
        var watchdog: AutoCloseable? = null

        log.debug { "리더 선출 성공 (suspend): lockName=$lockName" }
        try {
            val startedAt = Instant.now()
            val delegate: SuspendExtendDelegate = LettuceSuspendLockExtendDelegate(lock)
            val identity = LockIdentity(
                lockName = lockName,
                kind = LockIdentity.AnnotationKind.SINGLE,
                factoryBeanName = LETTUCE_SUSPEND_FACTORY_BEAN_NAME,
            )
            val handle = LeaderLockHandle.real(
                identity = identity,
                token = token,
                acquiredAtNanos = acquiredAtNanos,
                extendDelegate = delegate,
                auditLeaderId = auditLeaderId,
            )
            watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime, delegate, ERROR_CLASSIFIER)

            val record = historyRecorder?.let {
                LeaderLockHistoryRecord(
                    lockName = lockName,
                    token = token,
                    kind = LockIdentity.AnnotationKind.SINGLE,
                    acquiredAt = startedAt,
                    lockedUntil = startedAt.plusMillis(options.leaseTime.inWholeMilliseconds),
                )
            }
            val key = record?.let { historyRecorder.recordAcquired(it) }
            val effectiveKey: LeaderHistoryKey? = key
                ?: record?.let { LeaderHistoryKey(lockName = lockName, token = token) }

            return try {
                val result = withContext(AopScopeAccess.createLockHandleElement(handle)) {
                    action()
                }
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordCompleted(it, finishedAt, durationMs) }
                result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val finishedAt = Instant.now()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acquiredAtNanos)
                effectiveKey?.let { historyRecorder?.recordFailed(it, finishedAt, durationMs, e) }
                throw e
            }
        } finally {
            // NonCancellable: 코루틴 취소 시에도 lease 정리가 중단되지 않도록 보호
            withContext(NonCancellable) {
                watchdog?.let { LeaderLeaseAutoExtender.closeSuspend(it) }
                try {
                    if (lock.isHeldByCurrentInstance()) {
                        lock.unlock(options.minLeaseTime, acquiredAtNanos)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn(e) { "Fail to release lock. lockName=$lockName" }
                }
            }
        }
    }
}

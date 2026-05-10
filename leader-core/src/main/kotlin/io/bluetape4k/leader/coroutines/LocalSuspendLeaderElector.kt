package io.bluetape4k.leader.coroutines

import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.local.LocalLeaderStateRegistry
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.support.requireNotBlank
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Coroutines [Mutex]를 이용한 로컬(단일 JVM) suspend 리더 선출 구현체입니다.
 *
 * ## 동작
 * - 동일 `lockName`에 대해 코루틴 간 상호 배제(mutual exclusion)로 직렬 실행을 보장합니다.
 * - [Mutex]를 획득한 코루틴이 리더로서 `action`을 실행하며, 다른 코루틴은 해제될 때까지 suspend됩니다.
 * - 분산 환경이 아닌 단일 JVM 프로세스 내 코루틴 동시 실행 직렬화에 적합합니다.
 *
 * ## 주의
 * - [Mutex]는 재진입(re-entrancy)을 지원하지 않습니다.
 *   동일 코루틴에서 동일 `lockName`으로 중첩 호출하면 데드락이 발생합니다.
 *   재진입이 필요한 경우 [io.bluetape4k.leader.local.LocalLeaderElector] ([java.util.concurrent.locks.ReentrantLock] 기반)을 사용하세요.
 *
 * ```kotlin
 * val election = LocalSuspendLeaderElector()
 * val result = election.runIfLeader("job-lock") { "done" }
 * // result == "done"
 * ```
 */
class LocalSuspendLeaderElector(
    private val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): SuspendLeaderElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()
    private val states = LocalLeaderStateRegistry()

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    private fun getMutex(lockName: String): Mutex {
        lockName.requireNotBlank("lockName")
        return mutexes.computeIfAbsent(lockName) { Mutex() }
    }

    /**
     * [lockName]에 대한 [Mutex]를 획득하고 [action]을 직렬로 실행합니다.
     *
     * 다른 코루틴이 동일 [lockName]의 [Mutex]를 보유 중이면 해제될 때까지 suspend됩니다.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderElector()
     * val result = election.runIfLeader("job-lock") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName 리더 선출에 사용할 락 이름
     * @param action 리더 획득 성공 시 실행할 suspend 작업
     * @return [action] 실행 결과, 리더 획득 실패 시 `null`
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val mutex = getMutex(lockName)
        // withTimeoutOrNull 은 lock 획득 시도에만 적용합니다. action() 실행은 포함하지 않습니다.
        val acquired = withTimeoutOrNull(options.waitTime) {
            mutex.lock()
            true
        } ?: run {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
            return null
        }
        val startedAtNanos = System.nanoTime()
        states.acquireSingle(lockName, options.nodeId, options.leaseTime)
        val watchdog = LeaderLeaseAutoExtender.start(options.autoExtend, options.leaseTime) {
            states.extendSingle(lockName, options.leaseTime)
        }
        listeners.notifyElected(lockName)
        eventSubject.emit(LeaderElectionEvent.Elected(lockName))
        return try {
            action()
        } finally {
            withContext(NonCancellable) {
                watchdog.close()
                delayRemainingMinLeaseTime(startedAtNanos)
                states.releaseSingle(lockName)
                if (acquired) mutex.unlock()
                listeners.notifyRevoked(lockName)
                eventSubject.emit(LeaderElectionEvent.Revoked(lockName))
            }
        }
    }

    private suspend fun delayRemainingMinLeaseTime(startedAtNanos: Long) {
        val remaining = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime)
        if (remaining > kotlin.time.Duration.ZERO) {
            delay(remaining)
        }
    }

    override fun state(lockName: String): LeaderState =
        states.singleState(lockName)
}

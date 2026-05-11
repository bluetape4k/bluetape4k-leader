package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.coroutines.flow.extensions.subject.PublishSubject
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionListener
import io.bluetape4k.leader.LeaderElectionListenerRegistry
import io.bluetape4k.leader.LeaderElectionListenerSupport
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.LeaderLockHandle
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.internal.CaptureScope
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.local.AbstractLocalLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderStateRegistry
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 코루틴 [Semaphore]를 이용한 로컬(단일 JVM) suspend 복수 리더 선출 구현체입니다.
 *
 * ## 동작
 * - `lockName`별로 `kotlinx.coroutines.sync.Semaphore(maxLeaders)`를 생성하여 동시 실행 수를 제한합니다.
 * - 슬롯이 가득 찬 경우 빈 슬롯이 생길 때까지 호출 코루틴이 suspend됩니다.
 * - [Semaphore.withPermit]으로 슬롯을 관리하며, 예외 시에도 반드시 반환됩니다.
 * - 분산 환경이 아닌 단일 JVM 프로세스 내 코루틴 동시 실행 제한에 적합합니다.
 *
 * ## [io.bluetape4k.leader.local.LocalLeaderGroupElector] 과의 차이
 * - [io.bluetape4k.leader.local.LocalLeaderGroupElector]은 `java.util.concurrent.Semaphore`(스레드 블로킹)를 사용합니다.
 * - 이 구현체는 `kotlinx.coroutines.sync.Semaphore`(코루틴 suspend)를 사용합니다.
 *
 * ```kotlin
 * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
 *
 * // 최대 3개 코루틴이 동시에 실행
 * val result = election.runIfLeader("batch-job") { processChunkSuspend() }
 *
 * // 상태 조회
 * println(election.state("batch-job"))
 * ```
 *
 * @param options 리더 그룹 선출 옵션. 기본값은 [LeaderGroupElectionOptions.Default]
 */
class LocalSuspendLeaderGroupElector private constructor(
    private val options: LeaderGroupElectionOptions,
): SuspendLeaderGroupElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

    companion object: KLogging() {
        /**
         * [LeaderGroupElectionOptions]을 이용해 [LocalSuspendLeaderGroupElector] 인스턴스를 생성합니다.
         *
         * ```kotlin
         * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
         * val result = election.runIfLeader("batch-job") { "done" }
         * // result == "done"
         * ```
         *
         * @param options 리더 그룹 선출 옵션. 기본값은 [LeaderGroupElectionOptions.Default]
         * @return [LocalSuspendLeaderGroupElector] 인스턴스
         */
        operator fun invoke(
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): LocalSuspendLeaderGroupElector =
            options
                .also { it.maxLeaders.requirePositiveNumber("maxLeaders") }
                .let(::LocalSuspendLeaderGroupElector)
    }

    private val semaphores = ConcurrentHashMap<String, Semaphore>()
    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = PublishSubject<LeaderElectionEvent>()
    private val states = LocalLeaderStateRegistry()

    override val events: Flow<LeaderElectionEvent> = eventSubject

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    private fun getSemaphore(lockName: String): Semaphore {
        lockName.requireNotBlank("lockName")
        return semaphores.computeIfAbsent(lockName) { Semaphore(maxLeaders) }
    }

    override val maxLeaders: Int = options.maxLeaders

    /**
     * [lockName]에 대해 현재 활성(실행 중인) 리더 수를 반환합니다.
     *
     * `maxLeaders - availablePermits`로 계산하므로 근사값입니다.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val count = election.activeCount("batch-job")
     * // count == 0  (아무도 실행 중이 아닐 때)
     * ```
     *
     * @param lockName 조회할 락 이름
     * @return 현재 활성 리더 수 (근사값)
     */
    override fun activeCount(lockName: String): Int =
        maxLeaders - getSemaphore(lockName).availablePermits

    /**
     * [lockName]에 대해 새 리더를 수용할 수 있는 남은 슬롯 수를 반환합니다.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val slots = election.availableSlots("batch-job")
     * // slots == 3  (아무도 실행 중이 아닐 때)
     * ```
     *
     * @param lockName 조회할 락 이름
     * @return 사용 가능한 슬롯 수 (근사값)
     */
    override fun availableSlots(lockName: String): Int =
        getSemaphore(lockName).availablePermits

    /**
     * [lockName]에 대한 현재 [LeaderGroupState] 스냅샷을 반환합니다.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val state = election.state("batch-job")
     * // state.maxLeaders == 3
     * // state.activeCount == 0
     * // state.isEmpty == true
     * ```
     *
     * @param lockName 조회할 락 이름
     * @return 현재 리더 그룹 상태 스냅샷
     */
    override fun state(lockName: String): LeaderGroupState =
        states.groupState(lockName, maxLeaders, activeCount(lockName))

    /**
     * [lockName]의 [Semaphore] 슬롯을 획득하고 suspend [action]을 실행합니다.
     *
     * - 슬롯이 가득 찬 경우 빈 슬롯이 생길 때까지 코루틴이 suspend됩니다.
     * - [action] 예외 발생 시에도 슬롯은 반드시 반환됩니다.
     *
     * ```kotlin
     * val election = LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
     * val result = election.runIfLeader("batch-job") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName 리더 그룹 선출에 사용할 락 이름
     * @param action 슬롯 획득 성공 시 실행할 suspend 작업
     * @return [action] 실행 결과, 슬롯 획득 실패 시 `null`
     */
    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        val semaphore = getSemaphore(lockName)
        // withTimeoutOrNull 은 semaphore 획득 시도에만 적용합니다. action() 실행은 포함하지 않습니다.
        val acquired = withTimeoutOrNull(options.waitTime) {
            semaphore.acquire()
            true
        } ?: run {
            listeners.notifySkipped(lockName)
            eventSubject.emit(LeaderElectionEvent.Skipped(lockName))
            return null
        }
        val startedAtNanos = System.nanoTime()
        val token = Base58.randomString(8)
        val lease = states.acquireGroup(lockName, options.nodeId, options.leaseTime, maxLeaders)
        val slot = requireNotNull(lease.slot) {
            "Group lease.slot must be non-null for lockName=$lockName, kind=GROUP"
        }

        val identity = LockIdentity(
            lockName = lockName,
            kind = LockIdentity.AnnotationKind.GROUP,
            factoryBeanName = AbstractLocalLeaderGroupElector.LOCAL_GROUP_FACTORY_BEAN_NAME,
            groupParams = LockIdentity.GroupParams(maxLeaders),
        )
        val lastExtendDeadlineRef = AtomicReference(Instant.EPOCH)
        val delegate = object : ExtendDelegate {
            private val _lastExtendDeadline = lastExtendDeadlineRef
            override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline
            override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome {
                val extended = states.extendGroup(lockName, slot, lockAtMostFor)
                return if (extended) {
                    ExtendOutcome.Extended(Instant.now().plusMillis(lockAtMostFor.inWholeMilliseconds))
                } else {
                    ExtendOutcome.NotHeld
                }
            }
            override fun isHeld(): Boolean = states.isSlotHeld(lockName, slot)
        }

        val handle = LeaderLockHandle.real(
            identity = identity,
            token = token,
            acquiredAtNanos = startedAtNanos,
            slotId = slot.toString(),
            extendDelegate = delegate,
        )
        val watchdog = LeaderLeaseAutoExtender.start(false, options.leaseTime, delegate)
        listeners.notifyElected(lockName)
        eventSubject.emit(LeaderElectionEvent.Elected(lockName))
        return try {
            withContext(LockHandleElement(handle)) {
                CaptureScope.runWithCaptureSuspend(handle) {
                    action()
                }
            }
        } finally {
            withContext(NonCancellable) {
                watchdog.close()
                delayRemainingMinLeaseTime(startedAtNanos)
                states.releaseGroup(lockName, lease)
                if (acquired) semaphore.release()
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
}

package io.bluetape4k.leader

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private const val EVENT_BUFFER_CAPACITY = 64

/**
 * `ListeningLeaderElector` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
 */
class ListeningLeaderElector(
    private val delegate: LeaderElector,
): LeaderElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = MutableSharedFlow<LeaderElectionEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<LeaderElectionEvent> = eventSubject.asSharedFlow()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    override fun state(lockName: String): LeaderState =
        delegate.state(lockName)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            val leader = delegate.state(lockName).leader
            listeners.notifyElected(lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(lockName, leader))
            try {
                action()
            } finally {
                listeners.notifyRevoked(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
            }
        }
        if (!elected) {
            listeners.notifySkipped(lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Skipped(lockName))
        }
        return result
    }

    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(slot) {
            elected = true
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                action()
            } finally {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
            }
        }
        if (!elected) {
            listeners.notifySkipped(slot.lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Skipped(slot.lockName))
        }
        return result
    }

    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        var elected = false
        val result = delegate.runIfLeaderResult(slot) {
            elected = true
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                action()
            } finally {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
            }
        }
        if (!elected && result is LeaderRunResult.Skipped) {
            listeners.notifySkipped(slot.lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Skipped(slot.lockName))
        }
        return result
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            val leader = delegate.state(lockName).leader
            listeners.notifyElected(lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(lockName, leader))
            try {
                action().whenComplete { _, _ ->
                    listeners.notifyRevoked(lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                CompletableFuture.failedFuture(e)
            }
        }.whenComplete { value, failure ->
            if (!elected.get() && failure == null && value == null) {
                listeners.notifySkipped(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Skipped(lockName))
            }
        }
    }

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeader(slot, executor) {
            elected.set(true)
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                action().whenComplete { _, _ ->
                    listeners.notifyRevoked(slot.lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                CompletableFuture.failedFuture(e)
            }
        }.whenComplete { value, failure ->
            if (!elected.get() && failure == null && value == null) {
                listeners.notifySkipped(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Skipped(slot.lockName))
            }
        }
    }

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeaderResult(slot, executor) {
            elected.set(true)
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                action().whenComplete { _, _ ->
                    listeners.notifyRevoked(slot.lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                CompletableFuture.failedFuture(e)
            }
        }.whenComplete { result, failure ->
            if (!elected.get() && failure == null && result is LeaderRunResult.Skipped) {
                listeners.notifySkipped(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Skipped(slot.lockName))
            }
        }
    }
}

/**
 * `ListeningLeaderGroupElector` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
 */
class ListeningLeaderGroupElector(
    private val delegate: LeaderGroupElector,
): LeaderGroupElector, LeaderElectionListenerRegistry, LeaderElectionEventPublisher {

    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = MutableSharedFlow<LeaderElectionEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<LeaderElectionEvent> = eventSubject.asSharedFlow()

    override val maxLeaders: Int get() = delegate.maxLeaders

    override fun addListener(listener: LeaderElectionListener): AutoCloseable =
        listeners.addListener(listener)

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.removeListener(listener)

    override fun activeCount(lockName: String): Int =
        delegate.activeCount(lockName)

    override fun availableSlots(lockName: String): Int =
        delegate.availableSlots(lockName)

    override fun state(lockName: String): LeaderGroupState =
        delegate.state(lockName)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        var elected = false
        val result = delegate.runIfLeader(lockName) {
            elected = true
            listeners.notifyElected(lockName, null)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(lockName, null))
            try {
                action()
            } finally {
                listeners.notifyRevoked(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
            }
        }
        if (!elected) {
            listeners.notifySkipped(lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Skipped(lockName))
        }
        return result
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val elected = AtomicBoolean(false)
        return delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            listeners.notifyElected(lockName, null)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(lockName, null))
            try {
                action().whenComplete { _, _ ->
                    listeners.notifyRevoked(lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                CompletableFuture.failedFuture(e)
            }
        }.whenComplete { value, failure ->
            if (!elected.get() && failure == null && value == null) {
                listeners.notifySkipped(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Skipped(lockName))
            }
        }
    }
}

/**
 * `LeaderElector`는 blocking leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param listeners `listeners` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderElector =
    ListeningLeaderElector(this).apply {
        listeners.forEach { addListener(it) }
    }

/**
 * `LeaderGroupElector`는 여러 slot을 허용하는 blocking group leader election 실행자입니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param listeners `listeners` 호출 또는 상태 계산에 필요한 값입니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun LeaderGroupElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderGroupElector =
    ListeningLeaderGroupElector(this).apply {
        listeners.forEach { addListener(it) }
    }

package io.bluetape4k.leader

import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsAware
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.resolveLeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private const val EVENT_BUFFER_CAPACITY = 64

/**
 * leader election 실행을 위임하면서 listener callback과 `LeaderElectionEvent` 흐름을 함께 발행합니다.
 *
 * @property delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
 */
class ListeningLeaderElector(
    private val delegate: LeaderElector,
): LeaderElector,
   LeaderLeaseAcquirerSupport,
   LeaderElectionListenerRegistry,
   LeaderElectionEventPublisher,
   LeaderBackendDiagnosticsAware {

    companion object: KLogging()

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

    private val listeners = LeaderElectionListenerSupport()
    private val eventSubject = MutableSharedFlow<LeaderElectionEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<LeaderElectionEvent> = eventSubject.asSharedFlow()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override val leaseCapabilityAvailable: Boolean
        get() = ((delegate as? LeaderLeaseAcquirerSupport)?.leaseCapabilityAvailable ?: delegate) is LeaderLeaseAcquirer

    override val leaseAcquirerDelegate: LeaderLeaseAcquirer by lazy {
        (delegate as? LeaderLeaseAcquirer).requireNotNull {
            "The listening elector delegate does not expose request-lease capability"
        }
    }

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
        val source = delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            val leader = delegate.state(lockName).leader
            listeners.notifyElected(lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(lockName, leader))
            try {
                LeaderFutureBridge.observe(action()) { _, _ ->
                    listeners.notifyRevoked(lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                failedCompletableFutureOf(e)
            }
        }
        return LeaderFutureBridge.observe(source) { value, failure ->
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
        val source = delegate.runAsyncIfLeader(slot, executor) {
            elected.set(true)
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                LeaderFutureBridge.observe(action()) { _, _ ->
                    listeners.notifyRevoked(slot.lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                failedCompletableFutureOf(e)
            }
        }
        return LeaderFutureBridge.observe(source) { value, failure ->
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
        val source = delegate.runAsyncIfLeaderResult(slot, executor) {
            elected.set(true)
            val leader = delegate.state(slot.lockName).leader
            listeners.notifyElected(slot.lockName, leader)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(slot.lockName, leader))
            try {
                LeaderFutureBridge.observe(action()) { _, _ ->
                    listeners.notifyRevoked(slot.lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(slot.lockName))
                failedCompletableFutureOf(e)
            }
        }
        return LeaderFutureBridge.observe(source) { result, failure ->
            if (!elected.get() && failure == null && result is LeaderRunResult.Skipped) {
                listeners.notifySkipped(slot.lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Skipped(slot.lockName))
            }
        }
    }
}

/**
 * group leader election 실행을 위임하면서 listener callback과 `LeaderElectionEvent` 흐름을 함께 발행합니다.
 *
 * @property delegate 실제 leader election 동작을 수행하는 위임 객체입니다.
 */
class ListeningLeaderGroupElector(
    private val delegate: LeaderGroupElector,
): LeaderGroupElector,
   LeaderElectionListenerRegistry,
   LeaderElectionEventPublisher,
   LeaderBackendDiagnosticsAware {

    override val backendDiagnosticsProvider: LeaderBackendDiagnosticsProvider?
        get() = delegate.resolveLeaderBackendDiagnosticsProvider()

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
        val source = delegate.runAsyncIfLeader(lockName, executor) {
            elected.set(true)
            listeners.notifyElected(lockName, null)
            eventSubject.tryEmit(LeaderElectionEvent.Elected.fromLease(lockName, null))
            try {
                LeaderFutureBridge.observe(action()) { _, _ ->
                    listeners.notifyRevoked(lockName)
                    eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                }
            } catch (e: Throwable) {
                listeners.notifyRevoked(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Revoked(lockName))
                failedCompletableFutureOf(e)
            }
        }
        return LeaderFutureBridge.observe(source) { value, failure ->
            if (!elected.get() && failure == null && value == null) {
                listeners.notifySkipped(lockName)
                eventSubject.tryEmit(LeaderElectionEvent.Skipped(lockName))
            }
        }
    }
}

/**
 * 지정한 listener들을 등록한 `ListeningLeaderElector` wrapper를 반환합니다.
 * @param listeners 선출·skip·해제 이벤트를 받을 초기 listener 목록
 * @return 이 elector를 위임 대상으로 사용하는 listening wrapper
 */
fun LeaderElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderElector =
    ListeningLeaderElector(this).apply {
        listeners.forEach { addListener(it) }
    }

/**
 * 지정한 listener들을 등록한 `ListeningLeaderGroupElector` wrapper를 반환합니다.
 * @param listeners 선출·skip·해제 이벤트를 받을 초기 listener 목록
 * @return 이 group elector를 위임 대상으로 사용하는 listening wrapper
 */
fun LeaderGroupElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderGroupElector =
    ListeningLeaderGroupElector(this).apply {
        listeners.forEach { addListener(it) }
    }

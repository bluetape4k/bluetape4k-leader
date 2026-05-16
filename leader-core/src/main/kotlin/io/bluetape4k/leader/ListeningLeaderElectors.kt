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
 * Decorates a [LeaderElector] with listener callbacks and hot lifecycle events.
 *
 * ## Behavior / Contract
 * - Preserves the election, result, and exception behavior of [delegate].
 * - Calls registered [LeaderElectionListener] callbacks around successful or skipped leader actions.
 * - Exposes the same lifecycle as [events] through a non-suspending [MutableSharedFlow] publisher.
 * - Emits listener callbacks before the corresponding [events] item.
 * - Buffers up to [EVENT_BUFFER_CAPACITY] events and drops the oldest buffered event under back-pressure; this is
 *   not a guaranteed-delivery stream.
 *
 * ```kotlin
 * val election = redisLeaderElector.withListeners()
 * val job = scope.launch {
 *     election.events.collect { event -> println(event) }
 * }
 * ```
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
            listeners.notifyElected(lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Elected(lockName))
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
            listeners.notifyElected(lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Elected(lockName))
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
 * Decorates a [LeaderGroupElector] with listener callbacks and hot lifecycle events.
 *
 * ## Behavior / Contract
 * - Preserves group election, slot-count, result, and exception behavior of [delegate].
 * - Calls registered [LeaderElectionListener] callbacks around successful or skipped group-slot actions.
 * - Exposes lifecycle events through a non-suspending [MutableSharedFlow] publisher.
 * - Emits listener callbacks before the corresponding [events] item.
 * - Buffers up to [EVENT_BUFFER_CAPACITY] events and drops the oldest buffered event under back-pressure; this is
 *   not a guaranteed-delivery stream.
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
            listeners.notifyElected(lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Elected(lockName))
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
            listeners.notifyElected(lockName)
            eventSubject.tryEmit(LeaderElectionEvent.Elected(lockName))
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
 * Wraps this [LeaderElector] with listener callbacks and event publishing.
 */
fun LeaderElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderElector =
    ListeningLeaderElector(this).apply {
        listeners.forEach { addListener(it) }
    }

/**
 * Wraps this [LeaderGroupElector] with listener callbacks and event publishing.
 */
fun LeaderGroupElector.withListeners(vararg listeners: LeaderElectionListener): ListeningLeaderGroupElector =
    ListeningLeaderGroupElector(this).apply {
        listeners.forEach { addListener(it) }
    }

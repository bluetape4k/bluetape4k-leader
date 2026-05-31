package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * Synchronous listener that receives leader election lifecycle events.
 *
 * ## Behavior / Contract
 * - [onElected] is called immediately before the user action runs, after this call acquires a leader or group slot.
 *   Implementations that need owner or expiry metadata should override [onElected] with [LeaderLease].
 * - [onSkipped] is called when a leader or group slot could not be acquired within the wait time and the user action is skipped.
 * - [onRevoked] is called after this call releases the leadership or slot it held — not on external lease-loss detection.
 * - Ordinary listener exceptions are logged and ignored so they do not affect the leader action result.
 *
 * ```kotlin
 * val listener = object : LeaderElectionListener {
 *     override fun onElected(lockName: String) {
 *         println("elected: $lockName")
 *     }
 * }
 * ```
 */
interface LeaderElectionListener {

    /** Called when a leader or group slot is acquired. */
    fun onElected(lockName: String) = Unit

    /**
     * Called when a leader or group slot is acquired, with a best-effort lease snapshot.
     *
     * Backends that cannot report precise expiry pass `null` or a [LeaderLease] whose
     * [LeaderLease.leaseUntil] is `null`. This callback is for observability only; callers must still use the
     * backend's atomic acquire path to decide ownership.
     */
    fun onElected(lockName: String, leader: LeaderLease?) {
        onElected(lockName)
    }

    /** Called after leadership or a group slot is released. */
    fun onRevoked(lockName: String) = Unit

    /** Called when a leader or group slot could not be acquired and the user action was skipped. */
    fun onSkipped(lockName: String) = Unit
}

/**
 * Leader election lifecycle event.
 *
 * In suspend contexts, collect [LeaderElectionEventPublisher.events] instead of using callbacks
 * to observe the leader execution lifecycle as a stream.
 */
sealed interface LeaderElectionEvent : Serializable {
    /** The lock name for which the event was emitted. */
    val lockName: String

    /**
     * Leader or group slot acquisition event.
     *
     * ## Behavior / Contract
     * - [leader] is the full lease snapshot when the backend can report it at election time.
     * - [leaderId] is the elected audit identity. When [leader] is present, this should match
     *   [LeaderLease.auditLeaderId].
     * - [leaseExpiry] is the absolute time at which the lease expires. When [leader] is present, this should
     *   match [LeaderLease.leaseUntil].
     * - `null` leader or expiry means the backend could not report precise metadata for this event.
     */
    data class Elected @JvmOverloads constructor(
        override val lockName: String,
        val leaderId: String? = null,
        val leaseExpiry: Instant? = null,
        val leader: LeaderLease? = null,
    ) : LeaderElectionEvent, Serializable {
        companion object {
            private const val serialVersionUID = 2L

            /** Creates an elected event from a best-effort [LeaderLease] snapshot. */
            fun fromLease(lockName: String, leader: LeaderLease?): Elected =
                Elected(
                    lockName = lockName,
                    leaderId = leader?.auditLeaderId,
                    leaseExpiry = leader?.leaseUntil,
                    leader = leader,
                )
        }
    }

    /** Event emitted when leadership or a group slot is released. */
    data class Revoked(override val lockName: String) : LeaderElectionEvent {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** Event emitted when a leader or group slot could not be acquired and the user action was skipped. */
    data class Skipped(override val lockName: String) : LeaderElectionEvent {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

/**
 * Contract that exposes a leader election lifecycle event stream.
 *
 * ## Behavior / Contract
 * - [events] remains the coroutine-native hot stream.
 * - Callback registration helpers collect [events] in a caller-owned [CoroutineScope].
 * - Closing the returned [AutoCloseable] cancels only that callback collection job.
 * - Callback exceptions are logged and ignored so observability hooks do not affect leader execution.
 *
 * ```kotlin
 * val handle = publisher.onElected(applicationScope) { event ->
 *     println("elected ${event.lockName}")
 * }
 * handle.close()
 * ```
 */
interface LeaderElectionEventPublisher {

    /**
     * Leader election event stream.
     *
     * Implementations use a hot event source internally and deliver events that occur while a collector is active.
     */
    val events: Flow<LeaderElectionEvent>

    /**
     * Registers [listener] for every leader election event.
     *
     * The caller owns [scope]. Close the returned handle to stop collecting this publisher for [listener].
     */
    fun onEvent(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onEvent", LeaderElectionEvent::class.java, listener)

    /**
     * Registers [listener] for [LeaderElectionEvent.Elected] events.
     */
    fun onElected(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Elected>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onElected", LeaderElectionEvent.Elected::class.java, listener)

    /**
     * Registers [listener] for [LeaderElectionEvent.Revoked] events.
     */
    fun onRevoked(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Revoked>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onRevoked", LeaderElectionEvent.Revoked::class.java, listener)

    /**
     * Registers [listener] for [LeaderElectionEvent.Skipped] events.
     */
    fun onSkipped(
        scope: CoroutineScope,
        listener: Consumer<LeaderElectionEvent.Skipped>,
    ): AutoCloseable =
        subscribeToEvents(scope, "onSkipped", LeaderElectionEvent.Skipped::class.java, listener)
}

private fun <T : LeaderElectionEvent> LeaderElectionEventPublisher.subscribeToEvents(
    scope: CoroutineScope,
    callbackName: String,
    eventType: Class<T>,
    listener: Consumer<T>,
): AutoCloseable {
    val job = events
        .onEach { event ->
            if (eventType.isInstance(event)) {
                val typedEvent = eventType.cast(event)
                runCatching { listener.accept(typedEvent) }
                    .onFailure { e ->
                        LeaderElectionEventPublisherCallbackLogger.log.warn(e) {
                            "LeaderElectionEventPublisher $callbackName callback failed and was ignored. " +
                                "lockName=${event.lockName}"
                        }
                    }
            }
        }
        .launchIn(scope)

    return AutoCloseable { job.cancel() }
}

private object LeaderElectionEventPublisherCallbackLogger : KLogging()

/**
 * Contract for registering and unregistering [LeaderElectionListener] instances.
 *
 * Implementations also allow unregistering the same listener by closing the returned [AutoCloseable].
 */
interface LeaderElectionListenerRegistry {

    /**
     * Registers [listener] and returns a handle that unregisters it when closed.
     */
    fun addListener(listener: LeaderElectionListener): AutoCloseable

    /**
     * Unregisters [listener].
     *
     * @return `true` if the listener was actually registered and has been removed
     */
    fun removeListener(listener: LeaderElectionListener): Boolean
}

/**
 * Thread-safe default implementation of [LeaderElectionListenerRegistry].
 *
 * Uses [CopyOnWriteArrayList] so that registrations or unregistrations during listener dispatch
 * do not affect the stable snapshot used by the current dispatch.
 */
open class LeaderElectionListenerSupport : LeaderElectionListenerRegistry {

    private val listeners = CopyOnWriteArrayList<LeaderElectionListener>()

    override fun addListener(listener: LeaderElectionListener): AutoCloseable {
        listeners.addIfAbsent(listener)
        return AutoCloseable { removeListener(listener) }
    }

    override fun removeListener(listener: LeaderElectionListener): Boolean =
        listeners.remove(listener)

    /** Dispatches an elected event to all registered listeners. */
    fun notifyElected(lockName: String, leader: LeaderLease? = null) {
        notify(lockName, "onElected") { it.onElected(lockName, leader) }
    }

    /** Dispatches a revoked event to all registered listeners. */
    fun notifyRevoked(lockName: String) {
        notify(lockName, "onRevoked") { it.onRevoked(lockName) }
    }

    /** Dispatches a skipped event to all registered listeners. */
    fun notifySkipped(lockName: String) {
        notify(lockName, "onSkipped") { it.onSkipped(lockName) }
    }

    private fun notify(
        lockName: String,
        callbackName: String,
        callback: (LeaderElectionListener) -> Unit,
    ) {
        listeners.forEach { listener ->
            runCatching { callback(listener) }
                .onFailure { e ->
                    log.warn(e) {
                        "LeaderElectionListener $callbackName failed and was ignored. lockName=$lockName"
                    }
                }
        }
    }

    private companion object : KLogging()
}

package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.flow.Flow
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Synchronous listener that receives leader election lifecycle events.
 *
 * ## Behavior / Contract
 * - [onElected] is called immediately before the user action runs, after this call acquires a leader or group slot.
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
sealed interface LeaderElectionEvent {
    /** The lock name for which the event was emitted. */
    val lockName: String

    /**
     * Leader or group slot acquisition event.
     *
     * ## Behavior / Contract
     * - [leaderId] is the identity of the node that won the election. Populated by local electors
     *   and backends that carry identity; `null` when identity is unavailable (e.g. decorator path).
     * - [leaseExpiry] is the absolute time at which the lease expires. Currently always `null`;
     *   reserved for future backend implementations that can report expiry at election time.
     */
    data class Elected @JvmOverloads constructor(
        override val lockName: String,
        val leaderId: String? = null,
        val leaseExpiry: Instant? = null,
    ) : LeaderElectionEvent, Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /** Event emitted when leadership or a group slot is released. */
    data class Revoked(override val lockName: String) : LeaderElectionEvent

    /** Event emitted when a leader or group slot could not be acquired and the user action was skipped. */
    data class Skipped(override val lockName: String) : LeaderElectionEvent
}

/**
 * Contract that exposes a leader election lifecycle event stream.
 */
interface LeaderElectionEventPublisher {

    /**
     * Leader election event stream.
     *
     * Implementations use a hot event source internally and deliver events that occur while a collector is active.
     */
    val events: Flow<LeaderElectionEvent>
}

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
    fun notifyElected(lockName: String) {
        notify(lockName, "onElected") { it.onElected(lockName) }
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

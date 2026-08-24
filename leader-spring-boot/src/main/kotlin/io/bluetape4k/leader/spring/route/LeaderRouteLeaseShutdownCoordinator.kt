package io.bluetape4k.leader.spring.route

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/** Monotonic shutdown state owner for a LEASE route runtime. */
internal class LeaderRouteLeaseShutdownCoordinator(
    private val drainTimeout: Duration,
    private val activeLeases: () -> Int = { 0 },
    private val activeAcquires: () -> Int = { 0 },
    private val residualLeases: () -> Int = { 0 },
    private val cleanupComplete: () -> Boolean = { true },
    private val releaseHandles: () -> Unit = {},
    private val disposeSchedulers: () -> Unit = {},
) : AutoCloseable {

    private companion object {
        const val DRAIN_POLL_MILLIS = 1L
    }

    private val state = AtomicReference(State.RUNNING)
    private val lifecycleMonitor = Any()
    private val registeredHandles = IdentityHashMap<Any, () -> Unit>()

    val runtimeState: State get() = state.get()
    fun acceptsAcquire(): Boolean = runtimeState == State.RUNNING
    fun allowsCleanup(): Boolean = runtimeState in setOf(State.RUNNING, State.QUIESCING, State.DRAINING)

    fun quiesce(): Boolean = synchronized(lifecycleMonitor) {
        state.compareAndSet(State.RUNNING, State.QUIESCING)
    }

    /** handle publication과 shutdown drain 사이를 선형화합니다. */
    fun registerHandle(key: Any, release: () -> Unit): Boolean = synchronized(lifecycleMonitor) {
        if (state.get() != State.RUNNING) return@synchronized false
        registeredHandles[key] = release
        true
    }

    fun unregisterHandle(key: Any) = synchronized(lifecycleMonitor) {
        registeredHandles.remove(key)
    }

    /** Drains active handles within the configured bounded timeout. */
    @Suppress("ReturnCount")
    fun drain(): State {
        synchronized(lifecycleMonitor) {
            if (state.compareAndSet(State.RUNNING, State.QUIESCING).not() && state.get() != State.QUIESCING) {
                return state.get()
            }
            if (!state.compareAndSet(State.QUIESCING, State.DRAINING)) return state.get()
        }
        val registered = synchronized(lifecycleMonitor) { registeredHandles.values.toList() }
        registered.forEach { release -> runCatching { release() } }
        runCatching { releaseHandles() }
        val deadline = System.nanoTime() + drainTimeout.inWholeNanoseconds.coerceAtLeast(0L)
        while (System.nanoTime() < deadline) {
            if (isDrained()) {
                state.compareAndSet(State.DRAINING, State.DRAINED)
                return state.get()
            }
            try {
                Thread.sleep(DRAIN_POLL_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        state.compareAndSet(State.DRAINING, State.CLOSED_WITH_LEAKS)
        disposeSchedulers()
        return state.get()
    }

    private fun isDrained(): Boolean =
        activeLeases() == 0 && activeAcquires() == 0 && residualLeases() == 0 && cleanupComplete()

    override fun close() {
        when (drain()) {
            State.DRAINED -> {
                disposeSchedulers()
                state.compareAndSet(State.DRAINED, State.CLOSED)
            }
            State.CLOSED_WITH_LEAKS, State.CLOSED -> Unit
            State.RUNNING, State.QUIESCING, State.DRAINING -> Unit
        }
    }

    enum class State {
        RUNNING,
        QUIESCING,
        DRAINING,
        DRAINED,
        CLOSED,
        CLOSED_WITH_LEAKS,
    }
}

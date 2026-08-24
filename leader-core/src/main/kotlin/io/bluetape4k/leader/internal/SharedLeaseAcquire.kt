package io.bluetape4k.leader.internal

import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderSlot
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * 동일한 [LeaderSlot]에 대한 backend acquire 시도를 하나로 합치는 bounded shared attempt입니다.
 *
 * waiter는 물리 handle을 직접 소유하지 않고 reference만 보유합니다. 마지막 waiter가
 * terminalize할 때 물리 handle을 닫으며, backend callback이 늦게 도착해도 같은 attempt가
 * 보유한 handle을 정확히 한 번만 정리합니다.
 */
@Suppress("TooManyFunctions")
class SharedLeaseAcquire(
    private val scheduler: LeaseOperationScheduler,
    private val acquire: (LeaderSlot) -> LeaderLeaseHandle?,
    private val reserveAttempt: (() -> AutoCloseable?)? = null,
    private val onAdmissionRejected: (() -> Unit)? = null,
) : AutoCloseable {

    private val attempts = java.util.concurrent.ConcurrentHashMap<LeaderSlot, Attempt>()
    private val closed = AtomicBoolean(false)

    val activeAttempts: Int get() = attempts.size

    @Suppress("TooGenericExceptionCaught", "ReturnCount", "ThrowsCount")
    fun tryAcquire(slot: LeaderSlot, timeout: Duration = Duration.INFINITE): LeaderLeaseHandle? {
        if (closed.get()) return null
        val attempt = join(slot)
        startIfNeeded(attempt)
        val delegate = try {
            if (timeout == Duration.INFINITE) {
                attempt.future.get()
            } else {
                attempt.future.get(timeout.inWholeNanoseconds.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
            }
        } catch (_: TimeoutException) {
            releaseWaiter(attempt)
            return null
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            releaseWaiter(attempt)
            throw interrupted
        } catch (failure: java.util.concurrent.ExecutionException) {
            releaseWaiter(attempt)
            throw failure.cause ?: failure
        } catch (cancelled: java.util.concurrent.CancellationException) {
            releaseWaiter(attempt)
            throw cancelled
        }

        if (delegate == null) {
            releaseWaiter(attempt)
            return null
        }
        synchronized(attempt) {
            if (attempt.closed.get() || attempt.terminal.get() || attempt.delegate.get() !== delegate) {
                releaseWaiter(attempt)
                return null
            }
            return SharedHandle(delegate) { releaseWaiter(attempt) }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        attempts.values.toList().forEach(::closeAttempt)
    }

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
    private fun join(slot: LeaderSlot): Attempt {
        while (true) {
            if (closed.get()) return completedAttempt(slot)
            val existing = attempts[slot]
            if (existing == null) {
                val reservation = reserveAttempt?.invoke()
                if (reserveAttempt != null && reservation == null) {
                    onAdmissionRejected?.invoke()
                    return completedAttempt(slot)
                }
                val candidate = Attempt(slot, reservation)
                synchronized(candidate) { candidate.waiters.incrementAndGet() }
                if (attempts.putIfAbsent(slot, candidate) == null) return candidate
                closeReservationOnce(candidate)
                continue
            }

            var retry = false
            synchronized(existing) {
                when {
                    closed.get() || existing.closed.get() || existing.terminal.get() -> {
                        attempts.remove(slot, existing)
                        retry = true
                    }
                    existing.future.isDone && existing.delegate.get() != null -> {
                        // Published attempts remain mapped until their physical handle is
                        // released. A later request contends normally without replacing that
                        // mapping and opening a second backend acquire window.
                        return completedAttempt(slot)
                    }
                    else -> {
                        existing.waiters.incrementAndGet()
                        return existing
                    }
                }
            }
            if (retry) continue
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun startIfNeeded(attempt: Attempt) {
        if (!attempt.startAllowed || attempt.terminal.get()) {
            finish(attempt, published = false)
            return
        }
        if (closed.get()) {
            attempt.closed.set(true)
            attempt.future.complete(null)
            terminalizeForce(attempt)
            return
        }
        if (!attempt.started.compareAndSet(false, true)) return
        val task = scheduler.submit {
            try {
                val result = acquire(attempt.slot)
                publish(attempt, result)
            } catch (failure: Throwable) {
                synchronized(attempt) {
                    attempt.future.completeExceptionally(failure)
                }
                finish(attempt, published = false)
                throw failure
            }
        }
        if (task == null) {
            attempt.future.complete(null)
            finish(attempt, published = false)
        } else {
            attempt.task.set(task)
            if (attempt.waiters.get() == 0 && !attempt.future.isDone) task.cancel(true)
        }
    }

    private fun publish(attempt: Attempt, delegate: LeaderLeaseHandle?) {
        var accepted = false
        synchronized(attempt) {
            if (delegate != null && !attempt.closed.get() && !attempt.terminal.get()) {
                accepted = attempt.delegate.compareAndSet(null, delegate)
                if (accepted) attempt.future.complete(delegate)
            } else {
                attempt.future.complete(null)
            }
        }
        if (!accepted && delegate != null) delegate.release()
        finish(attempt, published = accepted)
    }

    private fun finish(attempt: Attempt, published: Boolean) {
        if (!published) attempts.remove(attempt.slot, attempt)
        closeReservationOnce(attempt)
        if (attempt.closed.get() || attempt.terminal.get()) {
            releaseDelegate(attempt)
        } else {
            terminalizeIfUnclaimed(attempt)
        }
    }

    private fun releaseWaiter(attempt: Attempt) {
        var cancel = false
        var terminalize = false
        synchronized(attempt) {
            if (attempt.waiters.decrementAndGet() == 0) {
                cancel = !attempt.future.isDone
                terminalize = true
            }
        }
        if (terminalize) terminalizeForce(attempt)
        if (cancel) attempt.task.get()?.cancel(true)
    }

    private fun terminalizeIfUnclaimed(attempt: Attempt) {
        val terminalize = synchronized(attempt) {
            if (attempt.waiters.get() != 0) {
                false
            } else {
                attempt.terminal.compareAndSet(false, true).also { changed ->
                    if (changed) {
                        attempts.remove(attempt.slot, attempt)
                        closeReservationOnce(attempt)
                    }
                }
            }
        }
        if (terminalize) releaseDelegate(attempt)
    }

    private fun terminalizeForce(attempt: Attempt) {
        val terminalize = synchronized(attempt) {
            attempt.future.complete(null)
            attempt.terminal.compareAndSet(false, true).also { changed ->
                if (changed) {
                    attempts.remove(attempt.slot, attempt)
                    closeReservationOnce(attempt)
                }
            }
        }
        if (terminalize) releaseDelegate(attempt)
    }

    private fun closeAttempt(attempt: Attempt) {
        synchronized(attempt) {
            attempt.closed.set(true)
            attempts.remove(attempt.slot, attempt)
            attempt.task.get()?.cancel(true)
            attempt.future.complete(null)
        }
        closeReservationOnce(attempt)
        terminalizeForce(attempt)
    }

    private fun releaseDelegate(attempt: Attempt) {
        if (attempt.delegateReleased.get()) return
        val delegate = attempt.delegate.get() ?: return
        if (attempt.delegateReleased.compareAndSet(false, true)) {
            attempt.delegate.compareAndSet(delegate, null)
            delegate.release()
        }
    }

    private fun completedAttempt(slot: LeaderSlot): Attempt = Attempt(slot, startAllowed = false).also {
        it.waiters.incrementAndGet()
        it.future.complete(null)
    }

    private fun closeReservationOnce(attempt: Attempt) {
        if (!attempt.reservationClosed.compareAndSet(false, true)) return
        try {
            attempt.reservation?.close()
        } catch (_: Exception) {
            // Admission resources are best-effort terminal bookkeeping.
        }
    }

    private class Attempt(
        val slot: LeaderSlot,
        val reservation: AutoCloseable? = null,
        val startAllowed: Boolean = true,
    ) {
        val started = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val terminal = AtomicBoolean(false)
        val reservationClosed = AtomicBoolean(false)
        val delegateReleased = AtomicBoolean(false)
        val waiters = AtomicInteger()
        val future = CompletableFuture<LeaderLeaseHandle?>()
        val task = AtomicReference<Future<*>?>(null)
        val delegate = AtomicReference<LeaderLeaseHandle?>(null)
    }

    private class SharedHandle(
        private val delegate: LeaderLeaseHandle,
        private val onRelease: () -> Unit,
    ) : LeaderLeaseHandle by delegate {
        private val released = AtomicBoolean(false)

        override fun release() {
            if (released.compareAndSet(false, true)) onRelease()
        }

        override fun close() = release()
    }
}

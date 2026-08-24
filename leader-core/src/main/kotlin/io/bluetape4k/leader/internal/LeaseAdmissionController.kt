package io.bluetape4k.leader.internal

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 요청별 lease의 acquire/cleanup/active admission을 고정 용량으로 제한합니다.
 *
 * backend를 호출하기 전에 acquire reservation을 확보하고, terminal callback 또는 handle
 * release에서만 반환합니다. 따라서 큐가 포화된 경우 backend 호출이 발생하지 않습니다.
 */
@Suppress("TooManyFunctions")
class LeaseAdmissionController(
    maxConcurrentAcquires: Int = 256,
    maxConcurrentCleanups: Int = 256,
    maxAcquireQueueDepth: Int = 1_024,
    maxCleanupQueueDepth: Int = 1_024,
    maxMvcBlockingAcquires: Int = maxConcurrentAcquires,
    maxActiveLeases: Int = 10_000,
    maxResidualLeases: Int = 1_024,
    maxWatchdogInFlight: Int = 256,
) {

    private val acquirePermits = Semaphore(maxConcurrentAcquires, true)
    private val acquireQueue = Semaphore(maxAcquireQueueDepth, true)
    private val cleanupPermits = Semaphore(maxConcurrentCleanups, true)
    private val cleanupQueue = Semaphore(maxCleanupQueueDepth, true)
    private val mvcWaiterPermits = Semaphore(maxMvcBlockingAcquires, true)
    private val activePermits = Semaphore(minOf(maxActiveLeases, maxResidualLeases), true)
    private val residualPermits = Semaphore(maxResidualLeases, true)
    private val watchdogPermits = Semaphore(maxWatchdogInFlight, true)

    private val acquireCount = AtomicInteger()
    private val cleanupCount = AtomicInteger()
    private val mvcWaiterCount = AtomicInteger()
    private val activeCount = AtomicInteger()
    private val residualCount = AtomicInteger()
    private val watchdogCount = AtomicInteger()

    val effectiveActiveCapacity: Int = minOf(maxActiveLeases, maxResidualLeases)

    @Suppress("ReturnCount")
    fun tryReserveAcquire(): AcquireReservation? {
        if (!acquirePermits.tryAcquire()) return null
        if (!acquireQueue.tryAcquire()) {
            acquirePermits.release()
            return null
        }
        acquireCount.incrementAndGet()
        return AcquireReservation(this)
    }

    @Suppress("ReturnCount")
    fun tryReserveCleanup(): CleanupReservation? {
        if (!cleanupPermits.tryAcquire()) return null
        if (!cleanupQueue.tryAcquire()) {
            cleanupPermits.release()
            return null
        }
        cleanupCount.incrementAndGet()
        return CleanupReservation(this)
    }

    fun tryReserveMvcWaiter(): MvcWaiterReservation? =
        if (mvcWaiterPermits.tryAcquire()) {
            mvcWaiterCount.incrementAndGet()
            MvcWaiterReservation(this)
        } else {
            null
        }

    fun tryReserveActive(): ActiveReservation? {
        if (!activePermits.tryAcquire()) return null
        activeCount.incrementAndGet()
        return ActiveReservation(this)
    }

    fun tryReserveResidual(): ResidualReservation? {
        if (!residualPermits.tryAcquire()) return null
        residualCount.incrementAndGet()
        return ResidualReservation(this)
    }

    fun tryReserveWatchdog(): WatchdogReservation? {
        if (!watchdogPermits.tryAcquire()) return null
        watchdogCount.incrementAndGet()
        return WatchdogReservation(this)
    }

    val acquireInFlight: Int get() = acquireCount.get()
    val cleanupInFlight: Int get() = cleanupCount.get()
    val mvcBlockingInFlight: Int get() = mvcWaiterCount.get()
    val activeLeases: Int get() = activeCount.get()
    val residualLeases: Int get() = residualCount.get()
    val watchdogInFlight: Int get() = watchdogCount.get()
    val acquireQueueAvailable: Int get() = acquireQueue.availablePermits()
    val cleanupQueueAvailable: Int get() = cleanupQueue.availablePermits()

    private fun releaseAcquire() {
        acquireQueue.release()
        acquirePermits.release()
        acquireCount.decrementAndGet()
    }

    private fun releaseCleanup() {
        cleanupQueue.release()
        cleanupPermits.release()
        cleanupCount.decrementAndGet()
    }

    private fun releaseMvcWaiter() {
        mvcWaiterPermits.release()
        mvcWaiterCount.decrementAndGet()
    }

    private fun releaseActive() {
        activePermits.release()
        activeCount.decrementAndGet()
    }

    private fun releaseResidual() {
        residualPermits.release()
        residualCount.decrementAndGet()
    }

    private fun releaseWatchdog() {
        watchdogPermits.release()
        watchdogCount.decrementAndGet()
    }

    sealed class Reservation protected constructor() : AutoCloseable {
        private val terminal = AtomicBoolean(false)

        final override fun close() {
            if (terminal.compareAndSet(false, true)) {
                releaseOnce()
            }
        }

        internal abstract fun releaseOnce()
    }

    class AcquireReservation internal constructor(private val owner: LeaseAdmissionController) : Reservation() {
        override fun releaseOnce() = owner.releaseAcquire()
    }

    class CleanupReservation internal constructor(private val owner: LeaseAdmissionController) : Reservation() {
        override fun releaseOnce() = owner.releaseCleanup()
    }

    class MvcWaiterReservation internal constructor(private val owner: LeaseAdmissionController) : Reservation() {
        override fun releaseOnce() = owner.releaseMvcWaiter()
    }

    class ActiveReservation internal constructor(private val owner: LeaseAdmissionController) : Reservation() {
        override fun releaseOnce() = owner.releaseActive()
    }

    class ResidualReservation internal constructor(private val owner: LeaseAdmissionController) : Reservation() {
        override fun releaseOnce() = owner.releaseResidual()
    }

    class WatchdogReservation internal constructor(private val owner: LeaseAdmissionController) : Reservation() {
        override fun releaseOnce() = owner.releaseWatchdog()
    }
}

package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.coroutines.LockHandleElement
import io.bluetape4k.leader.internal.BackendErrorKind
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.LockStateHolder
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * OBS-02 PR2의 user/watchdog renewal 경계가 동일한 terminal outcome을 관찰하는지 검증합니다.
 */
class LeaderLeaseExtensionBoundaryContractTest {

    @Test
    fun `blocking and suspend user events stay in the installed observation scope`() = runSuspendIO {
        val previous = leaderLeaseExtensionDispatcher
        val globalEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val aEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val bEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> runnable.run() }
        val global = LeaderLeaseExtensionObservers.addObserver(globalEvents::add)
        val a = LeaderLeaseExtensionObservers.addScopedObserver(aEvents::add)
        val b = LeaderLeaseExtensionObservers.addScopedObserver(bEvents::add)

        try {
            a.withScope {
                LockStateHolder.withPushed(realHandle(RecordingDelegate { ExtendOutcome.NotHeld })) {
                    LockExtender.extendActiveLockDetailed(30.seconds)
                }
            }
            withContext(a.asContextElement() + LockHandleElement(realHandle(RecordingSuspendDelegate {
                ExtendOutcome.NotHeld
            }))) {
                LockExtender.extendActiveLockDetailedSuspend(30.seconds)
            }

            globalEvents.size shouldBeEqualTo 2
            aEvents.size shouldBeEqualTo 2
            bEvents.size shouldBeEqualTo 0
        } finally {
            global.close()
            a.close()
            b.close()
            leaderLeaseExtensionDispatcher = previous
        }
    }

    @Test
    fun `blocking and suspend watchdog capture the scope installed at start`() {
        val previous = leaderLeaseExtensionDispatcher
        val globalEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val aEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val bEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> runnable.run() }
        val global = LeaderLeaseExtensionObservers.addObserver(globalEvents::add)
        val a = LeaderLeaseExtensionObservers.addScopedObserver(aEvents::add)
        val b = LeaderLeaseExtensionObservers.addScopedObserver(bEvents::add)
        val blocking = a.withScope {
            LeaderLeaseAutoExtender.start(
                enabled = true,
                leaseTime = 75.milliseconds,
                delegate = RecordingDelegate { ExtendOutcome.NotHeld },
            )
        }
        val suspend = a.withScope {
            LeaderLeaseAutoExtender.start(
                enabled = true,
                leaseTime = 75.milliseconds,
                delegate = RecordingSuspendDelegate { ExtendOutcome.NotHeld },
            )
        }

        try {
            await.atMost(5.seconds).untilAsserted {
                globalEvents.count { it.source == LeaderLeaseExtensionSource.WATCHDOG } shouldBeEqualTo 2
                aEvents.count { it.source == LeaderLeaseExtensionSource.WATCHDOG } shouldBeEqualTo 2
            }
            bEvents.size shouldBeEqualTo 0
        } finally {
            blocking.close()
            suspend.close()
            global.close()
            a.close()
            b.close()
            leaderLeaseExtensionDispatcher = previous
        }
    }

    @Test
    fun `revoked scope from an in-flight user extension cannot target a replacement scope`() {
        val previous = leaderLeaseExtensionDispatcher
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val globalEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val replacementEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val failure = AtomicReference<Throwable>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> runnable.run() }
        val global = LeaderLeaseExtensionObservers.addObserver(globalEvents::add)
        val stale = LeaderLeaseExtensionObservers.addScopedObserver { }
        val owner = Thread.startVirtualThread {
            try {
                stale.withScope {
                    LockStateHolder.withPushed(realHandle(RecordingDelegate {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        ExtendOutcome.NotHeld
                    })) {
                        LockExtender.extendActiveLockDetailed(30.seconds)
                    }
                }
            } catch (ex: Throwable) {
                failure.set(ex)
            }
        }

        entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        stale.close()
        val replacement = LeaderLeaseExtensionObservers.addScopedObserver(replacementEvents::add)
        try {
            release.countDown()
            owner.join(5_000)
            failure.get() shouldBeEqualTo null
            globalEvents.size shouldBeEqualTo 1
            replacementEvents.size shouldBeEqualTo 0
        } finally {
            release.countDown()
            global.close()
            stale.close()
            replacement.close()
            leaderLeaseExtensionDispatcher = previous
        }
    }

    @Test
    fun `blocking detailed extension publishes one user event after deadline update`() {
        withManualDispatcher { submitted, events ->
            val expireAt = Instant.now().plusSeconds(30)
            val delegate = RecordingDelegate { ExtendOutcome.Extended(expireAt) }
            val handle = realHandle(delegate, auditLeaderId = "leader-1")
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }

            try {
                LockStateHolder.withPushed(handle) {
                    LockExtender.extendActiveLockDetailed(30.seconds) shouldBeEqualTo ExtendOutcome.Extended(expireAt)
                }

                submitted.size shouldBeEqualTo 1
                submitted.single().run()
                events.size shouldBeEqualTo 1
                val event = events.single()
                event.source shouldBeEqualTo LeaderLeaseExtensionSource.USER
                event.execution shouldBeEqualTo LeaderLeaseExtensionExecution.BLOCKING
                event.outcome shouldBeEqualTo ExtendOutcome.Extended(expireAt)
                (event.elapsedNanos >= 0L).shouldBeTrue()
                event.context?.lockName shouldBeEqualTo "boundary-lock"
                event.context?.auditLeaderId shouldBeEqualTo "leader-1"
                delegate.lastExtendDeadline.get() shouldBeEqualTo expireAt
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `blocking event callback observes the updated deadline`() {
        val previous = leaderLeaseExtensionDispatcher
        val observedDeadline = AtomicReference<Instant>()
        val expireAt = Instant.now().plusSeconds(30)
        val delegate = RecordingDelegate { ExtendOutcome.Extended(expireAt) }
        leaderLeaseExtensionDispatcher = Executor { runnable -> runnable.run() }
        val registration = LeaderLeaseExtensionObservers.addObserver {
            observedDeadline.set(delegate.lastExtendDeadline.get())
        }

        try {
            LockStateHolder.withPushed(realHandle(delegate)) {
                LockExtender.extendActiveLockDetailed(30.seconds)
            }
            observedDeadline.get() shouldBeEqualTo expireAt
        } finally {
            registration.close()
            leaderLeaseExtensionDispatcher = previous
        }
    }

    @Test
    fun `boolean and java duration wrappers publish exactly one event`() {
        withManualDispatcher { submitted, events ->
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            val delegate = RecordingDelegate { ExtendOutcome.Extended(Instant.now().plusSeconds(30)) }

            try {
                LockStateHolder.withPushed(realHandle(delegate)) {
                    LockExtender.extendActiveLock(30.seconds).shouldBeTrue()
                    LockExtender.extendActiveLock(30.seconds.toJavaDuration()).shouldBeTrue()
                }

                submitted.forEach(Runnable::run)
                events.size shouldBeEqualTo 2
                events.all { it.source == LeaderLeaseExtensionSource.USER }.shouldBeTrue()
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `named blocking detailed extension keeps the active context`() {
        withManualDispatcher { submitted, events ->
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            val delegate = RecordingDelegate { ExtendOutcome.NotHeld }

            try {
                LockStateHolder.withPushed(realHandle(delegate, auditLeaderId = "named-leader")) {
                    LockExtender.extendActiveLockDetailed("boundary-lock", 30.seconds) shouldBeEqualTo
                        ExtendOutcome.NotHeld
                }

                submitted.single().run()
                events.single().source shouldBeEqualTo LeaderLeaseExtensionSource.USER
                events.single().execution shouldBeEqualTo LeaderLeaseExtensionExecution.BLOCKING
                events.single().context?.lockName shouldBeEqualTo "boundary-lock"
                events.single().context?.auditLeaderId shouldBeEqualTo "named-leader"
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `outside scope and fail open publish not held with bounded context`() {
        withManualDispatcher { submitted, events ->
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            try {
                LockExtender.extendActiveLockDetailed(30.seconds)
                LockStateHolder.withPushed(LeaderLockHandle.failOpen(identity())) {
                    LockExtender.extendActiveLockDetailed(30.seconds)
                }

                submitted.forEach(Runnable::run)
                events.size shouldBeEqualTo 2
                events[0].outcome shouldBeEqualTo ExtendOutcome.NotHeld
                events[0].elapsedNanos shouldBeEqualTo 0L
                events[0].context shouldBeEqualTo null
                events[1].outcome shouldBeEqualTo ExtendOutcome.NotHeld
                events[1].elapsedNanos shouldBeEqualTo 0L
                events[1].context?.lockName shouldBeEqualTo "boundary-lock"
                events[1].context?.auditLeaderId shouldBeEqualTo null
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `named mismatch publishes not held without guessing ownership`() {
        withManualDispatcher { submitted, events ->
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            val delegate = RecordingDelegate { ExtendOutcome.Extended(Instant.now().plusSeconds(30)) }
            try {
                LockStateHolder.withPushed(realHandle(delegate)) {
                    LockExtender.extendActiveLockDetailed("other-lock", 30.seconds) shouldBeEqualTo
                        ExtendOutcome.NotHeld
                }

                submitted.single().run()
                events.single().outcome shouldBeEqualTo ExtendOutcome.NotHeld
                events.single().elapsedNanos shouldBeEqualTo 0L
                events.single().context shouldBeEqualTo null
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `blocking delegate exception is rethrown after observation-only backend error`() {
        withManualDispatcher { submitted, events ->
            val cause = IllegalStateException("backend failure")
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            try {
                val thrown = assertFailsWith<IllegalStateException> {
                    LockStateHolder.withPushed(realHandle(RecordingDelegate { throw cause })) {
                        LockExtender.extendActiveLockDetailed(30.seconds)
                    }
                }
                thrown shouldBeSameInstanceAs cause

                submitted.single().run()
                events.single().outcome shouldBeEqualTo ExtendOutcome.BackendError(cause)
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `blocking cancellation is rethrown without publishing an event`() {
        withManualDispatcher { submitted, events ->
            val cancellation = CancellationException("cancelled")
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            try {
                val thrown = assertFailsWith<CancellationException> {
                    LockStateHolder.withPushed(realHandle(RecordingDelegate { throw cancellation })) {
                        LockExtender.extendActiveLockDetailed(30.seconds)
                    }
                }
                thrown shouldBeSameInstanceAs cancellation
                submitted.size shouldBeEqualTo 0
                events.size shouldBeEqualTo 0
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `blocking error is rethrown without publishing an event`() {
        withManualDispatcher { submitted, events ->
            val failure = AssertionError("fatal backend failure")
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            try {
                val thrown = assertFailsWith<AssertionError> {
                    LockStateHolder.withPushed(realHandle(RecordingDelegate { throw failure })) {
                        LockExtender.extendActiveLockDetailed(30.seconds)
                    }
                }
                thrown shouldBeSameInstanceAs failure
                submitted.size shouldBeEqualTo 0
                events.size shouldBeEqualTo 0
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `blocking wrong thread outcome is observed unchanged`() {
        withManualDispatcher { submitted, events ->
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            try {
                LockStateHolder.withPushed(realHandle(RecordingDelegate { ExtendOutcome.WrongThread })) {
                    LockExtender.extendActiveLockDetailed(30.seconds) shouldBeEqualTo ExtendOutcome.WrongThread
                }
                submitted.single().run()
                events.single().outcome shouldBeEqualTo ExtendOutcome.WrongThread
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `suspend detailed extension publishes suspend event and preserves cancellation`() = runSuspendIO {
        withManualDispatcherSuspend { submitted, events ->
            val expireAt = Instant.now().plusSeconds(30)
            val delegate = RecordingSuspendDelegate { ExtendOutcome.Extended(expireAt) }
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }

            try {
                withContext(LockHandleElement(realHandle(delegate, auditLeaderId = "suspend-leader"))) {
                    LockExtender.extendActiveLockDetailedSuspend(30.seconds) shouldBeEqualTo
                        ExtendOutcome.Extended(expireAt)
                }
                submitted.single().run()
                events.single().execution shouldBeEqualTo LeaderLeaseExtensionExecution.SUSPEND
                events.single().context?.auditLeaderId shouldBeEqualTo "suspend-leader"

                withContext(LockHandleElement(realHandle(delegate, auditLeaderId = "suspend-leader"))) {
                    LockExtender.extendActiveLockDetailedSuspend("boundary-lock", 30.seconds) shouldBeEqualTo
                        ExtendOutcome.Extended(expireAt)
                }
                submitted.drop(1).single().run()
                events.size shouldBeEqualTo 2
                events[1].execution shouldBeEqualTo LeaderLeaseExtensionExecution.SUSPEND

                val cancellation = CancellationException("cancelled")
                val cancellationDelegate = RecordingSuspendDelegate { throw cancellation }
                val thrownCancellation = assertFailsWith<CancellationException> {
                    withContext(LockHandleElement(realHandle(cancellationDelegate))) {
                        LockExtender.extendActiveLockDetailedSuspend(30.seconds)
                    }
                }
                thrownCancellation::class shouldBeEqualTo cancellation::class
                thrownCancellation.message shouldBeEqualTo cancellation.message
                submitted.size shouldBeEqualTo 2

                val cause = IllegalStateException("suspend backend failure")
                val exceptionDelegate = RecordingSuspendDelegate { throw cause }
                val thrown = assertFailsWith<IllegalStateException> {
                    withContext(LockHandleElement(realHandle(exceptionDelegate))) {
                        LockExtender.extendActiveLockDetailedSuspend(30.seconds)
                    }
                }
                thrown::class shouldBeEqualTo cause::class
                thrown.message shouldBeEqualTo cause.message
                submitted.drop(2).single().run()
                events.size shouldBeEqualTo 3
                val backendError = events[2].outcome.shouldBeInstanceOf<ExtendOutcome.BackendError>()
                backendError.cause::class shouldBeEqualTo cause::class
                backendError.cause.message shouldBeEqualTo cause.message
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `suspend boolean and named wrappers publish exactly one event each`() = runSuspendIO {
        withManualDispatcherSuspend { submitted, events ->
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            val delegate = RecordingSuspendDelegate { ExtendOutcome.Extended(Instant.now().plusSeconds(30)) }

            try {
                withContext(LockHandleElement(realHandle(delegate))) {
                    LockExtender.extendActiveLockSuspend(30.seconds).shouldBeTrue()
                    LockExtender.extendActiveLockSuspend("boundary-lock", 30.seconds).shouldBeTrue()
                }

                submitted.forEach(Runnable::run)
                events.size shouldBeEqualTo 2
                events.all {
                    it.source == LeaderLeaseExtensionSource.USER &&
                        it.execution == LeaderLeaseExtensionExecution.SUSPEND
                }.shouldBeTrue()
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `suspend error is rethrown without publishing an event`() = runSuspendIO {
        withManualDispatcherSuspend { submitted, events ->
            val failure = AssertionError("fatal suspend backend failure")
            val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
            try {
                val thrown = assertFailsWith<AssertionError> {
                    withContext(LockHandleElement(realHandle(RecordingSuspendDelegate { throw failure }))) {
                        LockExtender.extendActiveLockDetailedSuspend(30.seconds)
                    }
                }
                thrown::class shouldBeEqualTo failure::class
                thrown.message shouldBeEqualTo failure.message
                submitted.size shouldBeEqualTo 0
                events.size shouldBeEqualTo 0
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `watchdog blocking and suspend delegates publish source-specific events`() = runSuspendIO {
        val blockingEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val suspendEvents = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val blockingRegistration = LeaderLeaseExtensionObservers.addObserver {
            if (it.execution == LeaderLeaseExtensionExecution.BLOCKING) blockingEvents += it
        }
        val suspendRegistration = LeaderLeaseExtensionObservers.addObserver {
            if (it.execution == LeaderLeaseExtensionExecution.SUSPEND) suspendEvents += it
        }
        val blocking = RecordingDelegate { ExtendOutcome.Extended(Instant.now().plusMillis(250)) }
        val suspend = RecordingSuspendDelegate { ExtendOutcome.Extended(Instant.now().plusMillis(250)) }

        try {
            val blockingWatchdog = LeaderLeaseAutoExtender.start(true, 75.milliseconds, blocking)
            val suspendWatchdog = LeaderLeaseAutoExtender.start(true, 75.milliseconds, suspend)
            try {
                await
                    .atMost(5.seconds)
                    .withPollInterval(25.milliseconds)
                    .untilAsserted {
                        blockingEvents.isNotEmpty().shouldBeTrue()
                        suspendEvents.isNotEmpty().shouldBeTrue()
                    }
            } finally {
                blockingWatchdog.close()
                suspendWatchdog.close()
            }

            await
                .atMost(5.seconds)
                .withPollInterval(25.milliseconds)
                .untilAsserted {
                    blockingEvents.size shouldBeEqualTo blocking.calls.get()
                    suspendEvents.size shouldBeEqualTo suspend.calls.get()
                }

            blockingEvents.all { it.source == LeaderLeaseExtensionSource.WATCHDOG && it.context == null }
                .shouldBeTrue()
            suspendEvents.all { it.source == LeaderLeaseExtensionSource.WATCHDOG && it.context == null }
                .shouldBeTrue()
        } finally {
            blockingRegistration.close()
            suspendRegistration.close()
        }
    }

    @Test
    fun `delegate rejection is observed while scheduler admission rejection remains silent`() = runSuspendIO {
        val events = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
        try {
            val delegate = RecordingDelegate { throw RejectedExecutionException("backend rejected") }
            val watchdog = LeaderLeaseAutoExtender.start(true, 75.milliseconds, delegate)
            try {
                await
                    .atMost(5.seconds)
                    .withPollInterval(25.milliseconds)
                    .untilAsserted {
                        events.any { it.source == LeaderLeaseExtensionSource.WATCHDOG }.shouldBeTrue()
                    }
            } finally {
                watchdog.close()
            }

            val rejectedOutcome = events.single().outcome.shouldBeInstanceOf<ExtendOutcome.BackendError>()
            rejectedOutcome.cause.shouldBeInstanceOf<RejectedExecutionException>()

            LeaderLeaseAutoExtender.shutdown()
            val before = events.size
            LeaderLeaseAutoExtender.start(true, 75.milliseconds, RecordingDelegate { ExtendOutcome.NotHeld })
            LeaderLeaseAutoExtender.start(true, 75.milliseconds, RecordingSuspendDelegate { ExtendOutcome.NotHeld })
            events.size shouldBeEqualTo before
        } finally {
            LeaderLeaseAutoExtender.restart()
            registration.close()
        }
    }

    @Test
    fun `delegate rejection always stops blocking and suspend watchdogs despite transient classifier`() = runSuspendIO {
        val events = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
        val transientClassifier = io.bluetape4k.leader.internal.BackendErrorClassifier { BackendErrorKind.TRANSIENT }
        val blocking = RecordingDelegate { throw RejectedExecutionException("blocking rejected") }
        val suspend = RecordingSuspendDelegate { throw RejectedExecutionException("suspend rejected") }

        try {
            val blockingWatchdog = LeaderLeaseAutoExtender.start(true, 75.milliseconds, blocking, transientClassifier)
            val suspendWatchdog = LeaderLeaseAutoExtender.start(true, 75.milliseconds, suspend, transientClassifier)
            try {
                await
                    .atMost(5.seconds)
                    .withPollInterval(25.milliseconds)
                    .untilAsserted {
                        events.count { it.execution == LeaderLeaseExtensionExecution.BLOCKING }.shouldBeEqualTo(1)
                        events.count { it.execution == LeaderLeaseExtensionExecution.SUSPEND }.shouldBeEqualTo(1)
                    }
            } finally {
                blockingWatchdog.close()
                suspendWatchdog.close()
            }

            blocking.calls.get() shouldBeEqualTo 1
            suspend.calls.get() shouldBeEqualTo 1
        } finally {
            registration.close()
        }
    }

    @Test
    fun `watchdog cancellation rethrows and stops blocking modes without publishing an event`() = runSuspendIO {
        val events = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
        try {
            listOf(false, true).forEach { asyncExtend ->
                LeaderLeaseAutoExtender.configure(asyncExtend = asyncExtend)
                events.clear()
                val firstCall = CountDownLatch(1)
                val secondCall = CountDownLatch(1)
                val uncaught = AtomicReference<Throwable?>()
                val uncaughtLatch = CountDownLatch(1)
                val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
                if (asyncExtend) {
                    Thread.setDefaultUncaughtExceptionHandler { _, error ->
                        uncaught.compareAndSet(null, error)
                        uncaughtLatch.countDown()
                    }
                }
                val delegate = RecordingDelegate(
                    onCall = { count ->
                        when (count) {
                            1 -> firstCall.countDown()
                            2 -> secondCall.countDown()
                        }
                    },
                ) { throw CancellationException("watchdog cancelled") }
                val watchdog = LeaderLeaseAutoExtender.start(true, 75.milliseconds, delegate)
                try {
                    firstCall.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    if (asyncExtend) {
                        uncaughtLatch.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    }
                    secondCall.await(250, TimeUnit.MILLISECONDS).shouldBeFalse()
                } finally {
                    watchdog.close()
                    Thread.setDefaultUncaughtExceptionHandler(previousHandler)
                }
                delegate.calls.get() shouldBeEqualTo 1
                if (asyncExtend) {
                    (uncaught.get() is CancellationException).shouldBeTrue()
                }
            }
            events.size shouldBeEqualTo 0
        } finally {
            LeaderLeaseAutoExtender.configure(asyncExtend = false)
            registration.close()
        }
    }

    @Test
    fun `watchdog cancellation rethrows and preserves suspend scheduling without an event`() = runSuspendIO {
        val events = CopyOnWriteArrayList<LeaderLeaseExtensionEvent>()
        val registration = LeaderLeaseExtensionObservers.addObserver { events += it }
        try {
            listOf(false, true).forEach { asyncExtend ->
                LeaderLeaseAutoExtender.configure(asyncExtend = asyncExtend)
                events.clear()
                val firstCall = CountDownLatch(1)
                val secondCall = CountDownLatch(1)
                val invocation = java.util.concurrent.atomic.AtomicInteger()
                val delegate = RecordingSuspendDelegate(
                    onCall = { count ->
                        invocation.set(count)
                        when (count) {
                            1 -> firstCall.countDown()
                            2 -> secondCall.countDown()
                        }
                    },
                ) {
                    if (invocation.get() == 1) {
                        throw CancellationException("suspend watchdog cancelled")
                    }
                    ExtendOutcome.NotHeld
                }
                val watchdog = LeaderLeaseAutoExtender.start(true, 75.milliseconds, delegate)
                try {
                    firstCall.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    secondCall.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    await
                        .atMost(5.seconds)
                        .withPollInterval(25.milliseconds)
                        .untilAsserted {
                            events.count { it.execution == LeaderLeaseExtensionExecution.SUSPEND }
                                .shouldBeEqualTo(1)
                        }
                } finally {
                    watchdog.close()
                }
                delegate.calls.get() shouldBeEqualTo 2
            }
        } finally {
            LeaderLeaseAutoExtender.configure(asyncExtend = false)
            registration.close()
        }
    }

    @Test
    fun `watchdog start JVM descriptors remain stable for blocking and suspend delegates`() {
        val startMethods = LeaderLeaseAutoExtender::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("start-") }
            .map { method -> method.name to (method.parameterTypes.toList() to method.returnType) }

        val classifier = io.bluetape4k.leader.internal.BackendErrorClassifier::class.java
        val directPrefix = listOf(
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        val defaultPrefix = listOf(
            LeaderLeaseAutoExtender::class.java,
            Boolean::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
        )
        startMethods.toSet() shouldBeEqualTo setOf(
            "start-dWUq8MI" to (directPrefix + listOf(ExtendDelegate::class.java, classifier) to AutoCloseable::class.java),
            "start-dWUq8MI" to (directPrefix + listOf(SuspendExtendDelegate::class.java, classifier) to AutoCloseable::class.java),
            "start-dWUq8MI\$default" to
                (defaultPrefix + listOf(ExtendDelegate::class.java, classifier, Int::class.javaPrimitiveType, Any::class.java) to
                    AutoCloseable::class.java),
            "start-dWUq8MI\$default" to
                (defaultPrefix + listOf(SuspendExtendDelegate::class.java, classifier, Int::class.javaPrimitiveType, Any::class.java) to
                    AutoCloseable::class.java),
        )
    }

    private class RecordingDelegate(
        private val onCall: (Int) -> Unit = {},
        private val block: (Duration) -> ExtendOutcome,
    ) : ExtendDelegate {
        private val deadline = AtomicReference(Instant.EPOCH)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        override val lastExtendDeadline: AtomicReference<Instant> get() = deadline

        override fun extend(lockAtMostFor: Duration): ExtendOutcome {
            onCall(calls.incrementAndGet())
            return block(lockAtMostFor)
        }

        override fun isHeld(): Boolean = true
    }

    private class RecordingSuspendDelegate(
        private val onCall: (Int) -> Unit = {},
        private val block: suspend (Duration) -> ExtendOutcome,
    ) : SuspendExtendDelegate {
        private val deadline = AtomicReference(Instant.EPOCH)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        override val lastExtendDeadline: AtomicReference<Instant> get() = deadline

        override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome {
            onCall(calls.incrementAndGet())
            return block(lockAtMostFor)
        }

        override suspend fun isHeldSuspend(): Boolean = true
    }

    private fun identity(name: String = "boundary-lock") = LockIdentity(
        lockName = name,
        kind = LockIdentity.AnnotationKind.SINGLE,
        factoryBeanName = "boundary-factory",
    )

    private fun realHandle(
        delegate: ExtendDelegate,
        auditLeaderId: String? = null,
    ): LeaderLockHandle.Real = LeaderLockHandle.real(
        identity = identity(),
        token = "boundary-token",
        acquiredAtNanos = System.nanoTime(),
        extendDelegate = delegate,
        auditLeaderId = auditLeaderId,
    )

    private fun withManualDispatcher(block: (MutableList<Runnable>, MutableList<LeaderLeaseExtensionEvent>) -> Unit) {
        val previous = leaderLeaseExtensionDispatcher
        val submitted = mutableListOf<Runnable>()
        val events = mutableListOf<LeaderLeaseExtensionEvent>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> submitted += runnable }
        try {
            block(submitted, events)
        } finally {
            leaderLeaseExtensionDispatcher = previous
        }
    }

    private suspend fun withManualDispatcherSuspend(
        block: suspend (MutableList<Runnable>, MutableList<LeaderLeaseExtensionEvent>) -> Unit,
    ) {
        val previous = leaderLeaseExtensionDispatcher
        val submitted = mutableListOf<Runnable>()
        val events = mutableListOf<LeaderLeaseExtensionEvent>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> submitted += runnable }
        try {
            block(submitted, events)
        } finally {
            leaderLeaseExtensionDispatcher = previous
        }
    }
}

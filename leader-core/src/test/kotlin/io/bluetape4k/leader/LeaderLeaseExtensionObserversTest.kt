package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.withPollInterval
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaderLeaseExtensionObserversTest {

    @Test
    fun `event and context are immutable and redact ownership details`() {
        val context = LeaderLeaseExtensionContext(
            lockName = "secret-lock",
            auditLeaderId = "secret-leader",
        )
        val event = LeaderLeaseExtensionEvent(
            source = LeaderLeaseExtensionSource.USER,
            execution = LeaderLeaseExtensionExecution.BLOCKING,
            outcome = ExtendOutcome.Extended(Instant.parse("2026-01-01T00:00:00Z")),
            elapsedNanos = 42L,
            context = context,
        )

        context shouldBeEqualTo LeaderLeaseExtensionContext("secret-lock", "secret-leader")
        event shouldBeEqualTo LeaderLeaseExtensionEvent(
            LeaderLeaseExtensionSource.USER,
            LeaderLeaseExtensionExecution.BLOCKING,
            event.outcome,
            42L,
            context,
        )
        context.toString().contains("secret").shouldBeFalse()
        event.toString().contains("secret").shouldBeFalse()
        val backendEvent = event.copyForTest(ExtendOutcome.BackendError(IllegalStateException("secret-cause")))
        backendEvent.toString().contains("secret-cause").shouldBeFalse()
        backendEvent.toString().contains("BackendError").shouldBeTrue()

        val contextMethods = LeaderLeaseExtensionContext::class.java.declaredMethods.map { it.name }
        val eventMethods = LeaderLeaseExtensionEvent::class.java.declaredMethods.map { it.name }
        contextMethods.contains("copy").shouldBeFalse()
        contextMethods.contains("component1").shouldBeFalse()
        eventMethods.contains("copy").shouldBeFalse()
        eventMethods.contains("component1").shouldBeFalse()
        Serializable::class.java.isAssignableFrom(context.javaClass).shouldBeFalse()
        Serializable::class.java.isAssignableFrom(event.javaClass).shouldBeFalse()
    }

    @Test
    fun `addObserver publishes an event and close removes only its registration`() {
        withManualDispatcher { submitted ->
            val observed = AtomicReference<LeaderLeaseExtensionEvent>()
            val calls = AtomicInteger(0)
            val delivered = CountDownLatch(1)
            val observer = LeaderLeaseExtensionObserver { event ->
                calls.incrementAndGet()
                observed.set(event)
                delivered.countDown()
            }
            val registration = LeaderLeaseExtensionObservers.addObserver(observer)

            try {
                val expected = testEvent()
                LeaderLeaseExtensionObservers.publish(expected)
                submitted.size shouldBeEqualTo 1
                submitted.single().run()

                delivered.await(1, TimeUnit.SECONDS).shouldBeTrue()
                observed.get().shouldNotBeNull() shouldBeEqualTo expected
                calls.get() shouldBeEqualTo 1

                registration.close()
                LeaderLeaseExtensionObservers.publish(testEvent())
                submitted.size shouldBeEqualTo 1
                calls.get() shouldBeEqualTo 1
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `removeObserver removes every duplicate registration`() {
        withManualDispatcher { submitted ->
            val calls = AtomicInteger(0)
            val observer = LeaderLeaseExtensionObserver { calls.incrementAndGet() }
            val first = LeaderLeaseExtensionObservers.addObserver(observer)
            val second = LeaderLeaseExtensionObservers.addObserver(observer)

            try {
                LeaderLeaseExtensionObservers.publish(testEvent())
                submitted.size shouldBeEqualTo 2
                submitted.forEach(Runnable::run)
                calls.get() shouldBeEqualTo 2

                LeaderLeaseExtensionObservers.removeObserver(observer).shouldBeTrue()
                LeaderLeaseExtensionObservers.publish(testEvent())
                submitted.size shouldBeEqualTo 2
                calls.get() shouldBeEqualTo 2
            } finally {
                first.close()
                second.close()
            }
        }
    }

    @Test
    fun `observer exception is isolated from another observer`() {
        val delivered = CountDownLatch(1)
        val healthy = LeaderLeaseExtensionObserver {
            delivered.countDown()
        }
        val failing = LeaderLeaseExtensionObserver {
            throw IllegalStateException("observer failure")
        }
        val healthyRegistration = LeaderLeaseExtensionObservers.addObserver(healthy)
        val failingRegistration = LeaderLeaseExtensionObservers.addObserver(failing)

        try {
            LeaderLeaseExtensionObservers.publish(testEvent())
            delivered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            healthyRegistration.close()
            failingRegistration.close()
        }
    }

    @Test
    fun `observer cancellation exception is isolated from another observer`() {
        val delivered = CountDownLatch(1)
        val healthy = LeaderLeaseExtensionObserver {
            delivered.countDown()
        }
        val cancelling = LeaderLeaseExtensionObserver {
            throw CancellationException("observer cancellation")
        }
        val healthyRegistration = LeaderLeaseExtensionObservers.addObserver(healthy)
        val cancellingRegistration = LeaderLeaseExtensionObservers.addObserver(cancelling)

        try {
            LeaderLeaseExtensionObservers.publish(testEvent())
            delivered.await(5, TimeUnit.SECONDS).shouldBeTrue()
        } finally {
            healthyRegistration.close()
            cancellingRegistration.close()
        }
    }

    @Test
    fun `accepted callback runs after close while post-close publish is ignored`() {
        withManualDispatcher { submitted ->
            val closed = AtomicBoolean(false)
            val callbacks = AtomicInteger(0)
            val acceptedAfterClose = AtomicBoolean(false)
            val observer = LeaderLeaseExtensionObserver {
                callbacks.incrementAndGet()
                acceptedAfterClose.set(closed.get())
            }
            val registration = LeaderLeaseExtensionObservers.addObserver(observer)

            try {
                LeaderLeaseExtensionObservers.publish(testEvent())
                submitted.size shouldBeEqualTo 1

                closed.set(true)
                registration.close()
                submitted.single().run()

                acceptedAfterClose.get().shouldBeTrue()
                callbacks.get() shouldBeEqualTo 1

                LeaderLeaseExtensionObservers.publish(testEvent())
                submitted.size shouldBeEqualTo 1
                callbacks.get() shouldBeEqualTo 1
            } finally {
                registration.close()
            }
        }
    }

    @Test
    fun `hasObservers tracks registration lifecycle`() {
        val observer = LeaderLeaseExtensionObserver { }
        LeaderLeaseExtensionObservers.removeObserver(observer)
        LeaderLeaseExtensionObservers.hasObservers().shouldBeFalse()

        val registration = LeaderLeaseExtensionObservers.addObserver(observer)
        try {
            LeaderLeaseExtensionObservers.hasObservers().shouldBeTrue()
        } finally {
            registration.close()
        }

        LeaderLeaseExtensionObservers.hasObservers().shouldBeFalse()
    }

    @Test
    fun `scoped observers receive only their capability and wildcard receives all`() {
        withManualDispatcher { submitted ->
            val globalCalls = AtomicInteger()
            val aCalls = AtomicInteger()
            val bCalls = AtomicInteger()
            val global = LeaderLeaseExtensionObservers.addObserver { globalCalls.incrementAndGet() }
            val a = LeaderLeaseExtensionObservers.addScopedObserver { aCalls.incrementAndGet() }
            val b = LeaderLeaseExtensionObservers.addScopedObserver { bCalls.incrementAndGet() }

            try {
                LeaderLeaseExtensionObservers.publish(testEvent(), a)
                submitted.size shouldBeEqualTo 2
                submitted.forEach(Runnable::run)

                globalCalls.get() shouldBeEqualTo 1
                aCalls.get() shouldBeEqualTo 1
                bCalls.get() shouldBeEqualTo 0
            } finally {
                global.close()
                a.close()
                b.close()
            }
        }
    }

    @Test
    fun `accepted scoped callback may finish after close but later publish is rejected`() {
        withManualDispatcher { submitted ->
            val calls = AtomicInteger()
            val scope = LeaderLeaseExtensionObservers.addScopedObserver { calls.incrementAndGet() }

            LeaderLeaseExtensionObservers.publish(testEvent(), scope)
            submitted.size shouldBeEqualTo 1
            scope.close()
            submitted.single().run()

            calls.get() shouldBeEqualTo 1
            LeaderLeaseExtensionObservers.publish(testEvent(), scope)
            submitted.size shouldBeEqualTo 1
            calls.get() shouldBeEqualTo 1
        }
    }

    @Test
    fun `saturated admission counts only wildcard and matching scoped observers as drops`() {
        val entered = CountDownLatch(1024)
        val completed = CountDownLatch(1024)
        val release = CountDownLatch(1)
        val wildcardRegistrations = (1..4).map {
            LeaderLeaseExtensionObservers.addObserver {
                entered.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } finally {
                    completed.countDown()
                }
            }
        }
        val aCalls = AtomicInteger()
        val bCalls = AtomicInteger()
        val a = LeaderLeaseExtensionObservers.addScopedObserver { aCalls.incrementAndGet() }
        val b = LeaderLeaseExtensionObservers.addScopedObserver { bCalls.incrementAndGet() }

        try {
            repeat(256) { LeaderLeaseExtensionObservers.publish(testEvent()) }
            entered.await(10, TimeUnit.SECONDS).shouldBeTrue()

            val droppedBefore = LeaderLeaseExtensionObservers.droppedCount()
            LeaderLeaseExtensionObservers.publish(testEvent(), a)
            await
                .atMost(5.seconds)
                .withPollInterval(25.milliseconds)
                .untilAsserted {
                    LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore + 5
                }
            aCalls.get() shouldBeEqualTo 0
            bCalls.get() shouldBeEqualTo 0
        } finally {
            release.countDown()
            val drained = try {
                completed.await(10, TimeUnit.SECONDS)
            } finally {
                wildcardRegistrations.forEach(AutoCloseable::close)
                a.close()
                b.close()
            }
            drained.shouldBeTrue()
        }
    }

    @Test
    fun `closed scoped capability is not reused by a replacement registration`() {
        withManualDispatcher { submitted ->
            val staleCalls = AtomicInteger()
            val replacementCalls = AtomicInteger()
            val stale = LeaderLeaseExtensionObservers.addScopedObserver { staleCalls.incrementAndGet() }
            stale.close()
            val replacement = LeaderLeaseExtensionObservers.addScopedObserver { replacementCalls.incrementAndGet() }

            try {
                LeaderLeaseExtensionObservers.publish(testEvent(), stale)
                submitted.size shouldBeEqualTo 0
                LeaderLeaseExtensionObservers.publish(testEvent(), replacement)
                submitted.size shouldBeEqualTo 1
                submitted.single().run()

                staleCalls.get() shouldBeEqualTo 0
                replacementCalls.get() shouldBeEqualTo 1
            } finally {
                replacement.close()
            }
        }
    }

    @Test
    fun `scoped hasObservers ignores mismatched and revoked capabilities`() {
        val a = LeaderLeaseExtensionObservers.addScopedObserver { }
        val b = LeaderLeaseExtensionObservers.addScopedObserver { }

        try {
            LeaderLeaseExtensionObservers.hasObservers(a).shouldBeTrue()
            LeaderLeaseExtensionObservers.hasObservers(b).shouldBeTrue()
            a.close()
            LeaderLeaseExtensionObservers.hasObservers(a).shouldBeFalse()
            LeaderLeaseExtensionObservers.hasObservers(b).shouldBeTrue()
        } finally {
            a.close()
            b.close()
        }
    }

    @Test
    fun `removeObserver revokes its scoped capability`() {
        val observer = LeaderLeaseExtensionObserver { }
        val scope = LeaderLeaseExtensionObservers.addScopedObserver(observer)

        try {
            LeaderLeaseExtensionObservers.removeObserver(observer).shouldBeTrue()
            scope.isActive().shouldBeFalse()
            LeaderLeaseExtensionObservers.hasObservers(scope).shouldBeFalse()
        } finally {
            scope.close()
        }
    }

    @Test
    fun `Java fixture can construct and register through the public facade`() {
        val event = LeaderLeaseExtensionJavaApiFixture.event()

        event.source shouldBeEqualTo LeaderLeaseExtensionSource.USER
        event.execution shouldBeEqualTo LeaderLeaseExtensionExecution.BLOCKING
        LeaderLeaseExtensionJavaApiFixture.register().close()
        LeaderLeaseExtensionJavaApiFixture.registerAndRemove().shouldBeTrue()
        LeaderLeaseExtensionJavaApiFixture.droppedCount() shouldBeEqualTo
            LeaderLeaseExtensionObservers.droppedCount()
    }

    @Test
    fun `internal bridges are synthetic and public facade keeps only supported Java methods`() {
        val methods = LeaderLeaseExtensionObservers::class.java.declaredMethods
        val hasObservers = methods.first { it.name.startsWith("hasObservers") }
        val publish = methods.first { it.name.startsWith("publish") && it.parameterCount == 1 }

        hasObservers.isSynthetic.shouldBeTrue()
        publish.isSynthetic.shouldBeTrue()
        Modifier.isPublic(hasObservers.modifiers).shouldBeTrue()
        Modifier.isPublic(publish.modifiers).shouldBeTrue()

        methods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet() shouldBeEqualTo setOf("addObserver", "removeObserver", "droppedCount")
    }

    @Test
    fun `registration permit overflow drops delivery without blocking publisher`() {
        val entered = CountDownLatch(256)
        val completed = CountDownLatch(256)
        val release = CountDownLatch(1)
        val droppedBefore = LeaderLeaseExtensionObservers.droppedCount()
        val observer = LeaderLeaseExtensionObserver {
            entered.countDown()
            try {
                release.await(5, TimeUnit.SECONDS)
            } finally {
                completed.countDown()
            }
        }
        val registration = LeaderLeaseExtensionObservers.addObserver(observer)

        try {
            repeat(257) { LeaderLeaseExtensionObservers.publish(testEvent()) }

            entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            await
                .atMost(5.seconds)
                .withPollInterval(25.milliseconds)
                .untilAsserted {
                    LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore + 1
                }
        } finally {
            release.countDown()
            val drained = try {
                completed.await(10, TimeUnit.SECONDS)
            } finally {
                registration.close()
            }
            drained.shouldBeTrue()
        }
    }

    @Test
    fun `global admission bound drops every observer after all permits are occupied`() {
        val entered = CountDownLatch(1024)
        val completed = CountDownLatch(1024)
        val release = CountDownLatch(1)
        val registrations = (1..4).map {
            LeaderLeaseExtensionObservers.addObserver {
                entered.countDown()
                try {
                    release.await(5, TimeUnit.SECONDS)
                } finally {
                    completed.countDown()
                }
            }
        }

        try {
            repeat(256) {
                repeat(4) {
                    LeaderLeaseExtensionObservers.publish(testEvent())
                }
            }
            entered.await(10, TimeUnit.SECONDS).shouldBeTrue()

            val droppedBefore = LeaderLeaseExtensionObservers.droppedCount()
            LeaderLeaseExtensionObservers.publish(testEvent())
            await
                .atMost(5.seconds)
                .withPollInterval(25.milliseconds)
                .untilAsserted {
                    LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore + 4
                }
        } finally {
            release.countDown()
            val drained = try {
                completed.await(10, TimeUnit.SECONDS)
            } finally {
                registrations.forEach(AutoCloseable::close)
            }
            drained.shouldBeTrue()
        }
    }

    @Test
    fun `submission rejection records a drop and releases both permits`() {
        val previous = leaderLeaseExtensionDispatcher
        val droppedBefore = LeaderLeaseExtensionObservers.droppedCount()
        val registration = LeaderLeaseExtensionObservers.addObserver { }
        val extraRegistrations = mutableListOf<AutoCloseable>()

        try {
            leaderLeaseExtensionDispatcher = Executor {
                throw RejectedExecutionException("dispatcher unavailable")
            }
            LeaderLeaseExtensionObservers.publish(testEvent())
            LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore + 1

            val submitted = mutableListOf<Runnable>()
            leaderLeaseExtensionDispatcher = Executor { runnable -> submitted += runnable }
            repeat(3) { extraRegistrations += LeaderLeaseExtensionObservers.addObserver { } }
            repeat(256) {
                LeaderLeaseExtensionObservers.publish(testEvent())
            }
            submitted.size shouldBeEqualTo 1024
            LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore + 1
            submitted.forEach(Runnable::run)
        } finally {
            leaderLeaseExtensionDispatcher = previous
            extraRegistrations.forEach(AutoCloseable::close)
            registration.close()
        }
    }

    @Test
    fun `fatal submission error is rethrown after both permits are released`() {
        val previous = leaderLeaseExtensionDispatcher
        val droppedBefore = LeaderLeaseExtensionObservers.droppedCount()
        val registration = LeaderLeaseExtensionObservers.addObserver { }
        val extraRegistrations = mutableListOf<AutoCloseable>()

        try {
            leaderLeaseExtensionDispatcher = Executor {
                throw AssertionError("dispatcher failed")
            }
            assertFailsWith<AssertionError> {
                LeaderLeaseExtensionObservers.publish(testEvent())
            }
            LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore + 1

            val submitted = mutableListOf<Runnable>()
            leaderLeaseExtensionDispatcher = Executor { runnable -> submitted += runnable }
            repeat(3) { extraRegistrations += LeaderLeaseExtensionObservers.addObserver { } }
            repeat(256) {
                LeaderLeaseExtensionObservers.publish(testEvent())
            }
            submitted.size shouldBeEqualTo 1024
            LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore + 1
            submitted.forEach(Runnable::run)
        } finally {
            leaderLeaseExtensionDispatcher = previous
            extraRegistrations.forEach(AutoCloseable::close)
            registration.close()
        }
    }

    @Test
    fun `fatal callback error escapes while its permits are released`() {
        val uncaught = AtomicReference<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val firstCallback = AtomicBoolean(true)
        val entered = CountDownLatch(256)
        val completed = CountDownLatch(257)
        val release = CountDownLatch(1)
        val extraEntered = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val registration = LeaderLeaseExtensionObservers.addObserver {
            try {
                val call = calls.incrementAndGet()
                entered.countDown()
                if (firstCallback.compareAndSet(true, false)) {
                    throw AssertionError("fatal observer failure")
                }
                if (call <= 256) {
                    release.await(5, TimeUnit.SECONDS)
                } else {
                    extraEntered.countDown()
                }
            } finally {
                completed.countDown()
            }
        }

        try {
            Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught.set(throwable) }
            val droppedBefore = LeaderLeaseExtensionObservers.droppedCount()
            repeat(256) { LeaderLeaseExtensionObservers.publish(testEvent()) }

            entered.await(10, TimeUnit.SECONDS).shouldBeTrue()
            await
                .atMost(5.seconds)
                .withPollInterval(25.milliseconds)
                .untilAsserted {
                    uncaught.get().shouldNotBeNull()
                }
            LeaderLeaseExtensionObservers.publish(testEvent())
            extraEntered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            LeaderLeaseExtensionObservers.droppedCount() shouldBeEqualTo droppedBefore
            uncaught.get().shouldNotBeNull()::class.java shouldBeEqualTo AssertionError::class.java
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            release.countDown()
            val drained = try {
                completed.await(10, TimeUnit.SECONDS)
            } finally {
                registration.close()
            }
            drained.shouldBeTrue()
        }
    }

    @Test
    fun `concurrent add remove and publish preserve callback isolation`() {
        val callbacks = AtomicLong(0L)
        val initialCallback = CountDownLatch(1)
        val observer = LeaderLeaseExtensionObserver {
            callbacks.incrementAndGet()
            initialCallback.countDown()
        }
        val stableDelivered = CountDownLatch(1)
        val stableCallbacks = AtomicLong(0L)
        val stableObserver = LeaderLeaseExtensionObserver {
            stableCallbacks.incrementAndGet()
            stableDelivered.countDown()
        }
        val registration = LeaderLeaseExtensionObservers.addObserver(observer)
        val stableRegistration = LeaderLeaseExtensionObservers.addObserver(stableObserver)

        try {
            LeaderLeaseExtensionObservers.publish(testEvent())
            initialCallback.await(5, TimeUnit.SECONDS).shouldBeTrue()
            stableDelivered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            callbacks.set(0L)
            stableCallbacks.set(0L)
            MultithreadingTester()
                .rounds(2)
                .workers(4)
                .add { repeat(100) { LeaderLeaseExtensionObservers.publish(testEvent()) } }
                .add { repeat(100) { LeaderLeaseExtensionObservers.removeObserver(observer) } }
                .add { repeat(100) { LeaderLeaseExtensionObservers.addObserver(observer).close() } }
                .run()
            await
                .atMost(5.seconds)
                .withPollInterval(25.milliseconds)
                .untilAsserted {
                    (stableCallbacks.get() > 0L).shouldBeTrue()
                }
        } finally {
            registration.close()
            stableRegistration.close()
            LeaderLeaseExtensionObservers.removeObserver(observer)
        }
    }

    private fun testEvent(): LeaderLeaseExtensionEvent = LeaderLeaseExtensionEvent(
        source = LeaderLeaseExtensionSource.USER,
        execution = LeaderLeaseExtensionExecution.BLOCKING,
        outcome = ExtendOutcome.NotHeld,
        elapsedNanos = 1L,
        context = null,
    )

    private fun LeaderLeaseExtensionEvent.copyForTest(outcome: ExtendOutcome): LeaderLeaseExtensionEvent =
        LeaderLeaseExtensionEvent(source, execution, outcome, elapsedNanos, context)

    private fun withManualDispatcher(block: (MutableList<Runnable>) -> Unit) {
        val previous = leaderLeaseExtensionDispatcher
        val submitted = mutableListOf<Runnable>()
        leaderLeaseExtensionDispatcher = Executor { runnable -> submitted += runnable }
        try {
            block(submitted)
        } finally {
            leaderLeaseExtensionDispatcher = previous
        }
    }
}

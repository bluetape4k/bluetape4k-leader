package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLeaseExtensionContext
import io.bluetape4k.leader.LeaderLeaseExtensionEvent
import io.bluetape4k.leader.LeaderLeaseExtensionExecution
import io.bluetape4k.leader.LeaderLeaseExtensionObservers
import io.bluetape4k.leader.LeaderLeaseExtensionSource
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.micrometer.LeaderMetricTagOptions
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
import io.bluetape4k.leader.micrometer.TAG_LEADER_ID
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaseExtensionObservationRegistrationManagerTest {

    @Test
    fun `two Spring contexts sharing a registry keep one callback until the last context closes`() {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(handler)
        }
        val first = openContext(registry)
        val second = openContext(registry)

        try {
            val firstOwner = first.getBean(
                LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME,
                LeaseExtensionObservationScopeOwner::class.java,
            )
            val secondOwner = second.getBean(
                LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME,
                LeaseExtensionObservationScopeOwner::class.java,
            )
            LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 2
            first.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
            second.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
            firstOwner.current() shouldBeSameInstanceAs secondOwner.current()

            firstOwner.current()!!.withScope {
                LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
            }
            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                handler.stopped.size shouldBeEqualTo 1
            }

            first.close()
            secondOwner.current()!!.withScope {
                LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
            }
            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                handler.stopped.size shouldBeEqualTo 2
            }
        } finally {
            first.close()
            second.close()
        }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
    }

    @Test
    fun `parent and child contexts sharing an inherited registry keep one callback until both close`() {
        val registry = nonNoopRegistry()
        val parent = openContext(registry)
        val child = AnnotationConfigApplicationContext().apply {
            setParent(parent)
            register(LeaderObservationAutoConfiguration::class.java)
            refresh()
        }

        try {
            val parentOwner = parent.getBean(
                LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME,
                LeaseExtensionObservationScopeOwner::class.java,
            )
            val childOwner = child.getBean(
                LEASE_EXTENSION_OBSERVATION_SCOPE_OWNER_BEAN_NAME,
                LeaseExtensionObservationScopeOwner::class.java,
            )
            LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 2
            child.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
            parentOwner.current() shouldBeSameInstanceAs childOwner.current()
        } finally {
            child.close()
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 1
            parent.close()
        }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
    }

    @Test
    fun `same registry shares one callback and releases only after last context closes`() {
        val registry = ObservationRegistry.create()
        val handler = CollectingObservationHandler().also {
            registry.observationConfig().observationHandler(it)
        }
        val first = LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions())
        val second = LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions())

        try {
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 2
            first.scope shouldBeSameInstanceAs second.scope
            first.scope.withScope {
                LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
            }

            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                handler.stopped.size shouldBeEqualTo 1
            }

            first.close()
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 1
        } finally {
            second.close()
        }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
    }

    @Test
    fun `different registries have independent registrations`() {
        val firstRegistry = ObservationRegistry.create()
        val secondRegistry = ObservationRegistry.create()
        val firstHandler = CollectingObservationHandler().also {
            firstRegistry.observationConfig().observationHandler(it)
        }
        val secondHandler = CollectingObservationHandler().also {
            secondRegistry.observationConfig().observationHandler(it)
        }
        val first = LeaseExtensionObservationRegistrationManager.acquire(firstRegistry, LeaderObservationOptions())
        val second = LeaseExtensionObservationRegistrationManager.acquire(secondRegistry, LeaderObservationOptions())

        try {
            LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 2
            first.scope.withScope {
                LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
            }

            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                firstHandler.stopped.size shouldBeEqualTo 1
                secondHandler.stopped.size shouldBeEqualTo 0
            }
            second.scope.withScope { LockExtender.extendActiveLockDetailed(1.seconds) }
            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                firstHandler.stopped.size shouldBeEqualTo 1
                secondHandler.stopped.size shouldBeEqualTo 1
            }
        } finally {
            first.close()
            second.close()
        }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
    }

    @Test
    fun `different registries isolate opted-in identity and raw error in both close orders`() {
        listOf(CloseOrder.FIRST_THEN_SECOND, CloseOrder.SECOND_THEN_FIRST).forEach { closeOrder ->
            verifyOptedInIsolation(closeOrder)
        }
    }

    @Test
    fun `conflicting options fail fast without adding a second observer`() {
        val registry = ObservationRegistry.create()
        val first = LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions())

        try {
            assertFailsWith<IllegalStateException> {
                LeaseExtensionObservationRegistrationManager.acquire(
                    registry,
                    LeaderObservationOptions(includeLockName = true),
                )
            }
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 1
        } finally {
            first.close()
        }
    }

    @Test
    fun `parallel acquire and last close are linearized for one registry`() {
        val registry = nonNoopRegistry()
        val pool = Executors.newFixedThreadPool(8)
        val acquireGate = CountDownLatch(1)
        val handles = CopyOnWriteArrayList<AutoCloseable>()
        val acquireTasks = (1..8).map {
            pool.submit<AutoCloseable> {
                acquireGate.await(5, TimeUnit.SECONDS).shouldBeTrue()
                LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions()).also {
                    handles += it
                }
            }
        }

        try {
            acquireGate.countDown()
            acquireTasks.forEach { it.get(5, TimeUnit.SECONDS) }
            LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 8

            val closeGate = CountDownLatch(1)
            val closeTasks = handles.map { handle ->
                pool.submit {
                    closeGate.await(5, TimeUnit.SECONDS).shouldBeTrue()
                    handle.close()
                }
            }
            closeGate.countDown()
            closeTasks.forEach { it.get(5, TimeUnit.SECONDS) }

            LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 0
        } finally {
            acquireTasks.filterNot { it.isDone }.forEach { it.cancel(true) }
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS).shouldBeTrue()
            handles.forEach(AutoCloseable::close)
        }
    }

    @Test
    fun `acquire and close crossing keeps one live registration`() {
        val handler = CollectingObservationHandler()
        val registry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(handler)
        }
        val pool = Executors.newFixedThreadPool(2)
        var handle: LeaseExtensionObservationRegistrationManager.ManagedRegistration =
            LeaseExtensionObservationRegistrationManager.acquire(
            registry,
            LeaderObservationOptions(),
        )

        try {
            repeat(32) { index ->
                val barrier = CyclicBarrier(2)
                val closeTask = pool.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    handle.close()
                }
                val acquireTask = pool.submit<LeaseExtensionObservationRegistrationManager.ManagedRegistration> {
                    barrier.await(5, TimeUnit.SECONDS)
                    LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions())
                }

                closeTask.get(5, TimeUnit.SECONDS)
                handle = acquireTask.get(5, TimeUnit.SECONDS)
                LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
                LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 1
                handle.scope.withScope {
                    LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
                }
                await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                    handler.stopped.size shouldBeEqualTo index + 1
                }
            }
        } finally {
            handle.close()
            pool.shutdownNow()
        }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
        LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
        handler.stopped.size shouldBeEqualTo 32
    }

    @Test
    fun `last close releases registry and handle strong references`() {
        val references = acquireAndCloseWeakReferences()
        val registryCollected = AtomicBoolean(false)
        val handleCollected = AtomicBoolean(false)

        await.atMost(5.seconds.toJavaDuration()).untilAsserted {
            System.gc()
            if (!registryCollected.get()) {
                registryCollected.set(references.registryQueue.poll() === references.registryReference)
            }
            if (!handleCollected.get()) {
                handleCollected.set(references.handleQueue.poll() === references.handleReference)
            }
            registryCollected.get().shouldBeTrue()
            handleCollected.get().shouldBeTrue()
        }
    }

    private class CollectingObservationHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<ObservationSnapshot>()

        override fun onStop(context: Observation.Context) {
            stopped += ObservationSnapshot(
                name = context.name.orEmpty(),
                low = context.lowCardinalityKeyValues.associate { it.key to it.value },
                high = context.highCardinalityKeyValues.associate { it.key to it.value },
                error = context.error,
            )
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

    private fun verifyOptedInIsolation(closeOrder: CloseOrder) {
        val firstHandler = CollectingObservationHandler()
        val secondHandler = CollectingObservationHandler()
        val firstRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(firstHandler)
        }
        val secondRegistry = ObservationRegistry.create().apply {
            observationConfig().observationHandler(secondHandler)
        }
        val options = LeaderObservationOptions(
            includeLockName = true,
            includeLeaderId = true,
            includeExceptionDetails = true,
            tagOptions = LeaderMetricTagOptions.Raw,
        )
        val first = LeaseExtensionObservationRegistrationManager.acquire(firstRegistry, options)
        val second = LeaseExtensionObservationRegistrationManager.acquire(secondRegistry, options)
        val firstFailure = IllegalStateException("jdbc:password=first-secret")
        val secondFailure = IllegalArgumentException("token=second-secret")

        try {
            LeaderLeaseExtensionObservers.publish(
                extensionErrorEvent("first-lock", "first-leader", firstFailure),
                first.scope,
            )
            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                firstHandler.stopped.size shouldBeEqualTo 1
                secondHandler.stopped.size shouldBeEqualTo 0
            }
            assertOwnIdentity(firstHandler.stopped.single(), "first-lock", "first-leader", firstFailure)

            LeaderLeaseExtensionObservers.publish(
                extensionErrorEvent("second-lock", "second-leader", secondFailure),
                second.scope,
            )
            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                firstHandler.stopped.size shouldBeEqualTo 1
                secondHandler.stopped.size shouldBeEqualTo 1
            }
            assertOwnIdentity(secondHandler.stopped.single(), "second-lock", "second-leader", secondFailure)

            val closed = if (closeOrder == CloseOrder.FIRST_THEN_SECOND) first else second
            val remaining = if (closeOrder == CloseOrder.FIRST_THEN_SECOND) second else first
            val closedHandler = if (closeOrder == CloseOrder.FIRST_THEN_SECOND) firstHandler else secondHandler
            val remainingHandler = if (closeOrder == CloseOrder.FIRST_THEN_SECOND) secondHandler else firstHandler
            val closedCount = closedHandler.stopped.size
            val remainingCount = remainingHandler.stopped.size
            closed.close()

            LeaderLeaseExtensionObservers.publish(
                extensionErrorEvent("closed-lock", "closed-leader", IllegalStateException("closed-secret")),
                closed.scope,
            )
            val remainingFailure = IllegalStateException("remaining-secret")
            LeaderLeaseExtensionObservers.publish(
                extensionErrorEvent("remaining-lock", "remaining-leader", remainingFailure),
                remaining.scope,
            )
            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                closedHandler.stopped.size shouldBeEqualTo closedCount
                remainingHandler.stopped.size shouldBeEqualTo remainingCount + 1
            }
            assertOwnIdentity(
                remainingHandler.stopped.last(),
                "remaining-lock",
                "remaining-leader",
                remainingFailure,
            )
        } finally {
            first.close()
            second.close()
        }

        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
    }

    private fun extensionErrorEvent(
        lockName: String,
        leaderId: String,
        failure: Exception,
    ): LeaderLeaseExtensionEvent = LeaderLeaseExtensionEvent(
        source = LeaderLeaseExtensionSource.USER,
        execution = LeaderLeaseExtensionExecution.BLOCKING,
        outcome = ExtendOutcome.BackendError(failure),
        elapsedNanos = 1L,
        context = LeaderLeaseExtensionContext(lockName, leaderId),
    )

    private fun assertOwnIdentity(
        snapshot: ObservationSnapshot,
        lockName: String,
        leaderId: String,
        failure: Throwable,
    ) {
        snapshot.name shouldBeEqualTo "bluetape4k.leader.lease.extension"
        snapshot.low shouldBeEqualTo mapOf(
            "source" to "user",
            "execution" to "blocking",
            "outcome" to "backend_error",
            "result" to "error",
        )
        snapshot.high shouldBeEqualTo mapOf(
            "lock.name" to lockName,
            TAG_LEADER_ID to leaderId,
        )
        snapshot.error shouldBeSameInstanceAs failure
    }

    private enum class CloseOrder {
        FIRST_THEN_SECOND,
        SECOND_THEN_FIRST,
    }

    private data class ObservationSnapshot(
        val name: String,
        val low: Map<String, String>,
        val high: Map<String, String>,
        val error: Throwable?,
    )

    private fun nonNoopRegistry(): ObservationRegistry = ObservationRegistry.create().apply {
        observationConfig().observationHandler(NonNoopObservationHandler)
    }

    private fun openContext(registry: ObservationRegistry): AnnotationConfigApplicationContext =
        AnnotationConfigApplicationContext().apply {
            beanFactory.registerSingleton("observationRegistry", registry)
            register(LeaderObservationAutoConfiguration::class.java)
            refresh()
        }

    private fun acquireAndCloseWeakReferences(): WeakReferences {
        val registryQueue = ReferenceQueue<ObservationRegistry>()
        val handleQueue = ReferenceQueue<AutoCloseable>()
        val registry = ObservationRegistry.create()
        val handle: AutoCloseable =
            LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions())
        val registryReference = WeakReference(registry, registryQueue)
        val handleReference = WeakReference(handle, handleQueue)

        handle.close()
        LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 0
        LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 0

        return WeakReferences(
            registryReference = registryReference,
            registryQueue = registryQueue,
            handleReference = handleReference,
            handleQueue = handleQueue,
        )
    }

    private data class WeakReferences(
        val registryReference: WeakReference<ObservationRegistry>,
        val registryQueue: ReferenceQueue<ObservationRegistry>,
        val handleReference: WeakReference<AutoCloseable>,
        val handleQueue: ReferenceQueue<AutoCloseable>,
    )

    private object NonNoopObservationHandler : ObservationHandler<Observation.Context> {
        override fun supportsContext(context: Observation.Context): Boolean = true
    }
}

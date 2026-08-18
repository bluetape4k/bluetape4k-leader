package io.bluetape4k.leader.spring.metrics

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LockExtender
import io.bluetape4k.leader.micrometer.LeaderObservationOptions
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
            LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 2
            first.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
            second.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()

            LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
            await.atMost(5.seconds.toJavaDuration()).untilAsserted {
                handler.stopped.size shouldBeEqualTo 1
            }

            first.close()
            LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
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
            LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
            LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 2
            child.containsBean("leaseExtensionObserverRegistration").shouldBeTrue()
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
            LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld

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
            LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld

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
        var handle: AutoCloseable = LeaseExtensionObservationRegistrationManager.acquire(
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
                val acquireTask = pool.submit<AutoCloseable> {
                    barrier.await(5, TimeUnit.SECONDS)
                    LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions())
                }

                closeTask.get(5, TimeUnit.SECONDS)
                handle = acquireTask.get(5, TimeUnit.SECONDS)
                LeaseExtensionObservationRegistrationManager.registryCount() shouldBeEqualTo 1
                LeaseExtensionObservationRegistrationManager.referenceCount(registry) shouldBeEqualTo 1
                LockExtender.extendActiveLockDetailed(1.seconds) shouldBeEqualTo ExtendOutcome.NotHeld
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
        val stopped = CopyOnWriteArrayList<String>()

        override fun onStop(context: Observation.Context) {
            stopped += context.name.orEmpty()
        }

        override fun supportsContext(context: Observation.Context): Boolean = true
    }

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
        val handle = LeaseExtensionObservationRegistrationManager.acquire(registry, LeaderObservationOptions())
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

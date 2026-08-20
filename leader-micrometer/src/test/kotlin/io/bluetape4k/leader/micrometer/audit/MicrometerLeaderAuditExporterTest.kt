package io.bluetape4k.leader.micrometer.audit

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.leader.audit.LeaderAuditExportEvent
import io.bluetape4k.leader.audit.LeaderAuditExportObserver
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot
import io.bluetape4k.leader.audit.LeaderAuditExporter
import io.bluetape4k.leader.audit.LeaderAuditSubmitResult
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import io.bluetape4k.leader.LockIdentity
import io.bluetape4k.leader.micrometer.MicrometerNames
import io.bluetape4k.leader.micrometer.audit.MicrometerLeaderAuditExporterJavaContractTest
import io.micrometer.core.instrument.FunctionCounter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.ArrayDeque
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.ToDoubleFunction

class MicrometerLeaderAuditExporterTest {

    @Test
    fun `fixed audit meter catalog exposes only bounded outcome tags`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(
            snapshot(accepted = 2, droppedQueueFull = 3, droppedClosed = 4, diagnosticsClosed = true),
        )
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)

        val expectedNames = setOf(
            MicrometerNames.AUDIT_EXPORT_ACCEPTED,
            MicrometerNames.AUDIT_EXPORT_DROPPED,
            MicrometerNames.AUDIT_EXPORT_RETRIES,
            MicrometerNames.AUDIT_EXPORT_FAILURES,
            MicrometerNames.AUDIT_EXPORT_QUEUE_DEPTH,
            MicrometerNames.AUDIT_EXPORT_IN_FLIGHT,
            MicrometerNames.AUDIT_EXPORT_CANCELLED,
            MicrometerNames.AUDIT_EXPORT_REJECTIONS,
            MicrometerNames.AUDIT_EXPORT_OBSERVER_DROPPED,
            MicrometerNames.AUDIT_EXPORT_OBSERVER_REGISTRATION_DROPPED,
            MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_FAILURES,
            MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_CLOSED,
        )
        val auditMeters = registry.meters.filter { it.id.name in expectedNames }
        auditMeters.map { it.id.name }.toSet() shouldBeEqualTo expectedNames
        auditMeters.size shouldBeEqualTo 13

        val outcomeValues = auditMeters.flatMap { meter ->
            meter.id.tags.filter { it.key == MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME }
                .map { it.value }
        }.toSet()
        outcomeValues shouldBeEqualTo setOf("accepted", "queue_full", "closed", "retry", "failure", "cancelled", "rejected")
        auditMeters.flatMap { it.id.tags }.none { tag ->
            tag.key == "source" || tag.key == "transport" || tag.key == "lock.name" || tag.key == "endpoint"
        }.shouldBeTrue()

        registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
            ?.count() shouldBeEqualTo 2.0
        registry.find(MicrometerNames.AUDIT_EXPORT_DROPPED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "queue_full")
            .functionCounter()
            ?.count() shouldBeEqualTo 3.0
        registry.find(MicrometerNames.AUDIT_EXPORT_DROPPED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "closed")
            .functionCounter()
            ?.count() shouldBeEqualTo 4.0
        registry.find(MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_CLOSED)
            .gauge()
            ?.value() shouldBeEqualTo 1.0

        exporter.close()
    }

    @Test
    fun `decorator delegates public surface and close is idempotent`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 7))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val event = event()

        exporter.snapshot() shouldBeEqualTo delegate.snapshot()
        exporter.submit(event) shouldBeEqualTo LeaderAuditSubmitResult.ACCEPTED
        exporter.observe(LeaderAuditExportObserver { })
        exporter.close()
        exporter.close()
        delegate.closeCount shouldBeEqualTo 1
        exporter.submit(event) shouldBeEqualTo LeaderAuditSubmitResult.DROPPED_CLOSED
    }

    @Test
    fun `close waits for an admitted submit before closing the delegate`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot())
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        delegate.blockSubmissions(entered, release)
        val submitFinished = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)

        val submitThread = Thread {
            exporter.submit(event())
            submitFinished.countDown()
        }
        val closeThread = Thread {
            exporter.close()
            closeFinished.countDown()
        }
        submitThread.start()
        entered.await(1, TimeUnit.SECONDS).shouldBeTrue()
        closeThread.start()
        delegate.closeCount shouldBeEqualTo 0

        release.countDown()
        submitFinished.await(1, TimeUnit.SECONDS).shouldBeTrue()
        closeFinished.await(1, TimeUnit.SECONDS).shouldBeTrue()
        submitThread.join(1_000)
        closeThread.join(1_000)
        delegate.closeCount shouldBeEqualTo 1
        exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.DROPPED_CLOSED
    }

    @Test
    fun `submit drops immediately while close is closing the delegate`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot())
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        delegate.blockClose(closeEntered, closeRelease)
        val closeFinished = CountDownLatch(1)

        val closeThread = Thread {
            exporter.close()
            closeFinished.countDown()
        }
        closeThread.start()
        closeEntered.await(1, TimeUnit.SECONDS).shouldBeTrue()

        exporter.submit(event()) shouldBeEqualTo LeaderAuditSubmitResult.DROPPED_CLOSED

        closeRelease.countDown()
        closeFinished.await(1, TimeUnit.SECONDS).shouldBeTrue()
        closeThread.join(1_000)
    }

    @Test
    fun `duplicate active decorator fails without closing the winner`() {
        val registry = SimpleMeterRegistry()
        val winnerDelegate = SnapshotExporter(snapshot())
        val loserDelegate = SnapshotExporter(snapshot())
        val winner = MicrometerLeaderAuditExporter(winnerDelegate, registry)

        assertFailsWith<IllegalStateException> {
            MicrometerLeaderAuditExporter(loserDelegate, registry)
        }
        winnerDelegate.closeCount shouldBeEqualTo 0
        loserDelegate.closeCount shouldBeEqualTo 1
        winner.close()
    }

    @Test
    fun `wrapping the same delegate twice does not close the active owner`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot())
        val winner = MicrometerLeaderAuditExporter(delegate, registry)

        assertFailsWith<IllegalStateException> {
            MicrometerLeaderAuditExporter(delegate, registry)
        }
        delegate.closeCount shouldBeEqualTo 0

        winner.close()
        delegate.closeCount shouldBeEqualTo 1
    }

    @Test
    fun `wrapping the same delegate across registries does not close the active owner`() {
        val firstRegistry = SimpleMeterRegistry()
        val secondRegistry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot())
        val winner = MicrometerLeaderAuditExporter(delegate, firstRegistry)

        assertFailsWith<IllegalStateException> {
            MicrometerLeaderAuditExporter(delegate, secondRegistry)
        }
        delegate.closeCount shouldBeEqualTo 0

        winner.close()
        delegate.closeCount shouldBeEqualTo 1
    }

    @Test
    fun `foreign fixed meter registration fails before ownership transfer`() {
        val registry = SimpleMeterRegistry()
        val foreign = registry.counter(
            MicrometerNames.AUDIT_EXPORT_ACCEPTED,
            MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME,
            "accepted",
        )
        val delegate = SnapshotExporter(snapshot())

        assertFailsWith<IllegalStateException> {
            MicrometerLeaderAuditExporter(delegate, registry)
        }
        delegate.closeCount shouldBeEqualTo 1

        registry.remove(foreign)
        val retryDelegate = SnapshotExporter(snapshot())
        assertFailsWith<IllegalStateException> {
            MicrometerLeaderAuditExporter(retryDelegate, registry)
        }
        retryDelegate.closeCount shouldBeEqualTo 1

        val freshRegistry = SimpleMeterRegistry()
        val recovered = MicrometerLeaderAuditExporter(SnapshotExporter(snapshot()), freshRegistry)
        recovered.close()
    }

    @Test
    fun `failed construction keeps delegate ownership through cleanup`() {
        val failingRegistry = SimpleMeterRegistry()
        failingRegistry.counter(
            MicrometerNames.AUDIT_EXPORT_ACCEPTED,
            MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME,
            "accepted",
        )
        val secondRegistry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot())
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        delegate.blockClose(closeEntered, closeRelease)
        val failure = AtomicReference<Throwable?>(null)
        val construction = Thread {
            try {
                MicrometerLeaderAuditExporter(delegate, failingRegistry)
            } catch (caught: Throwable) {
                failure.set(caught)
            }
        }
        construction.start()
        closeEntered.await(1, TimeUnit.SECONDS).shouldBeTrue()

        assertFailsWith<IllegalStateException> {
            MicrometerLeaderAuditExporter(delegate, secondRegistry)
        }

        closeRelease.countDown()
        construction.join(1_000)
        failure.get().shouldNotBeNull()
        delegate.closeCount shouldBeEqualTo 1
    }

    @Test
    fun `partial meter registration retries only missing catalog without duplicate or foreign removal`() {
        val registry = FailingMeterRegistry(failOnSuccessfulAttempt = 5)
        val failedDelegate = SnapshotExporter(snapshot())

        assertFailsWith<IllegalStateException> {
            MicrometerLeaderAuditExporter(failedDelegate, registry)
        }
        failedDelegate.closeCount shouldBeEqualTo 1
        val acceptedMeterBefore = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
            .shouldNotBeNull()

        registry.allowRegistration()
        val recovered = MicrometerLeaderAuditExporter(SnapshotExporter(snapshot()), registry)
        val acceptedMeterAfter = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
            .shouldNotBeNull()

        registry.meters.count { it.id.name.startsWith("leader.audit.export.") } shouldBeEqualTo 13
        acceptedMeterAfter shouldBeEqualTo acceptedMeterBefore
        registry.duplicateRegistrationIds shouldBeEqualTo 0
        registry.removeCalls shouldBeEqualTo 0

        recovered.close()
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `registry retirement drains a queued weak key without a manager registry strong reference`() {
        val registry = SimpleMeterRegistry()
        val exporter = MicrometerLeaderAuditExporter(SnapshotExporter(snapshot()), registry)
        val store = registryManagerStore()
        val managers = privateField(store, "managers").get(store) as MutableMap<Any, Any>
        val key = managers.keys.single { (it as WeakReference<*>).get() === registry }
        val manager = managers.getValue(key)
        val registryReference = privateField(manager, "registryReference").get(manager) as WeakReference<*>

        (registryReference.get() === registry).shouldBeTrue()
        manager.javaClass.declaredFields.none { field ->
            MeterRegistry::class.java.isAssignableFrom(field.type)
        }.shouldBeTrue()
        privateField(manager, "registryReference").type shouldBeEqualTo WeakReference::class.java
        containsStrongReference(manager, registry).shouldBeFalse()

        val weakKey = key as WeakReference<*>
        weakKey.clear()
        weakKey.enqueue()
        managers.containsKey(key).shouldBeTrue()

        val replacementRegistry = SimpleMeterRegistry()
        val replacement = MicrometerLeaderAuditExporter(SnapshotExporter(snapshot()), replacementRegistry)

        managers.containsKey(key).shouldBeFalse()
        exporter.close()
        replacement.close()
    }

    @Test
    fun `close aggregates close entry delegate and final failures then permits replacement`() {
        val registry = SimpleMeterRegistry()
        val closeEntryFailure = IllegalStateException("close-entry failure")
        val delegateCloseFailure = IllegalStateException("delegate close failure")
        val finalSnapshotFailure = IllegalStateException("final snapshot failure")
        val delegate = SnapshotExporter(snapshot())
        delegate.scriptSnapshots(
            { throw closeEntryFailure },
            { throw finalSnapshotFailure },
        )
        delegate.failCloseWith(delegateCloseFailure)
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)

        val thrown = assertFailsWith<IllegalStateException> { exporter.close() }

        thrown shouldBeEqualTo delegateCloseFailure
        thrown.suppressed.toList() shouldBeEqualTo listOf(finalSnapshotFailure, closeEntryFailure)
        delegate.closeCount shouldBeEqualTo 1
        registry.find(MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_CLOSED)
            .gauge()
            ?.value() shouldBeEqualTo 1.0

        val repeated = assertFailsWith<IllegalStateException> { exporter.close() }
        repeated shouldBeEqualTo thrown
        delegate.closeCount shouldBeEqualTo 1

        val replacementDelegate = SnapshotExporter(snapshot(accepted = 3))
        val replacement = MicrometerLeaderAuditExporter(replacementDelegate, registry)
        registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
            ?.count() shouldBeEqualTo 3.0
        replacement.close()
    }

    @Test
    fun `close failure matrix preserves primary suppressed order and detached replacement`() {
        for (mask in 0 until 8) {
            val registry = SimpleMeterRegistry()
            val closeEntryFailure = if (mask and 1 != 0) {
                IllegalStateException("close-entry-$mask")
            } else {
                null
            }
            val delegateCloseFailure = if (mask and 2 != 0) {
                IllegalStateException("delegate-close-$mask")
            } else {
                null
            }
            val finalSnapshotFailure = if (mask and 4 != 0) {
                IllegalStateException("final-snapshot-$mask")
            } else {
                null
            }
            val delegate = SnapshotExporter(snapshot())
            delegate.scriptSnapshots(
                closeEntryFailure?.let { failure -> { throw failure } } ?: { snapshot(accepted = 1) },
                finalSnapshotFailure?.let { failure -> { throw failure } } ?: { snapshot(accepted = 2) },
            )
            delegate.failCloseWith(delegateCloseFailure)
            val exporter = MicrometerLeaderAuditExporter(delegate, registry)

            val expectedPrimary = delegateCloseFailure ?: finalSnapshotFailure ?: closeEntryFailure
            val expectedSuppressed = when {
                delegateCloseFailure != null -> listOfNotNull(finalSnapshotFailure, closeEntryFailure)
                finalSnapshotFailure != null -> listOfNotNull(closeEntryFailure)
                else -> emptyList()
            }
            if (expectedPrimary == null) {
                exporter.close()
            } else {
                val thrown = assertFailsWith<IllegalStateException> { exporter.close() }
                thrown shouldBeEqualTo expectedPrimary
                thrown.suppressed.toList() shouldBeEqualTo expectedSuppressed
            }
            delegate.closeCount shouldBeEqualTo 1
            registry.find(MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_CLOSED)
                .gauge()
                ?.value() shouldBeEqualTo 1.0

            val replacement = MicrometerLeaderAuditExporter(SnapshotExporter(snapshot(accepted = 3)), registry)
            replacement.close()
        }
    }

    @Test
    fun `cumulative monotonicity comparison does not allocate a boolean list`() {
        val source = Files.readString(
            Path.of(
                "src/main/kotlin/" +
                    "io/bluetape4k/leader/micrometer/audit/MicrometerLeaderAuditExporter.kt",
            ),
        )
        val comparison = source
            .substringAfter("private fun MicrometerLeaderAuditExporter.CumulativeValues.isNotLessThan")
            .substringBefore("\n\nprivate fun LeaderAuditExportSnapshot.asDetached")

        comparison.contains("listOf(").shouldBeFalse()
    }

    @Test
    fun `close then replacement keeps meter identity and cumulative offsets`() {
        val registry = SimpleMeterRegistry()
        val oldDelegate = SnapshotExporter(snapshot(accepted = 100))
        val old = MicrometerLeaderAuditExporter(oldDelegate, registry)
        val meterBefore = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
        meterBefore?.let { meter -> (meter as FunctionCounter).count() shouldBeEqualTo 100.0 }

        old.close()
        val replacementDelegate = SnapshotExporter(snapshot(accepted = 3))
        val replacement = MicrometerLeaderAuditExporter(replacementDelegate, registry)
        val meterAfter = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
        meterAfter shouldBeEqualTo meterBefore
        (meterAfter as FunctionCounter).count() shouldBeEqualTo 103.0
        replacement.close()
    }

    @Test
    fun `close entry regression keeps the last trusted cumulative baseline`() {
        val registry = SimpleMeterRegistry()
        val oldDelegate = SnapshotExporter(snapshot(accepted = 100))
        val old = MicrometerLeaderAuditExporter(oldDelegate, registry)
        val accepted = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
            .shouldNotBeNull()
        accepted.count() shouldBeEqualTo 100.0

        oldDelegate.setSnapshot(snapshot(accepted = 40))
        old.close()

        val replacementDelegate = SnapshotExporter(snapshot(accepted = 1))
        val replacement = MicrometerLeaderAuditExporter(replacementDelegate, registry)
        registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
            .shouldNotBeNull()
            .count() shouldBeEqualTo 101.0
        replacement.close()
    }

    @Test
    fun `closing metric poll keeps the trusted baseline before close completes`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 100))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val accepted = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
            .shouldNotBeNull()
        accepted.count() shouldBeEqualTo 100.0

        delegate.setSnapshot(snapshot(accepted = 40))
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        delegate.blockClose(closeEntered, closeRelease)
        val closeFinished = CountDownLatch(1)
        Thread {
            exporter.close()
            closeFinished.countDown()
        }.start()
        closeEntered.await(1, TimeUnit.SECONDS).shouldBeTrue()

        accepted.count() shouldBeEqualTo 100.0

        closeRelease.countDown()
        closeFinished.await(1, TimeUnit.SECONDS).shouldBeTrue()
    }

    @Test
    fun `source snapshot fields map to their fixed meter types`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(
            snapshot(
                retries = 5,
                terminalFailures = 6,
                cancellations = 7,
                executorRejections = 8,
                schedulerRejections = 9,
                observerDrops = 10,
                observerRegistrationDrops = 11,
                diagnosticsFatalErrors = 12,
                queued = 2,
                inFlight = 3,
            ),
        )
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)

        registry.find(MicrometerNames.AUDIT_EXPORT_RETRIES)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "retry")
            .meter().shouldNotBeNull().let { it is FunctionCounter }.shouldBeTrue()
        registry.find(MicrometerNames.AUDIT_EXPORT_FAILURES)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "failure")
            .meter().shouldNotBeNull().let { it is FunctionCounter }.shouldBeTrue()
        registry.find(MicrometerNames.AUDIT_EXPORT_QUEUE_DEPTH).meter()
            .shouldNotBeNull().let { it is Gauge }.shouldBeTrue()
        registry.find(MicrometerNames.AUDIT_EXPORT_IN_FLIGHT).meter()
            .shouldNotBeNull().let { it is Gauge }.shouldBeTrue()
        registry.find(MicrometerNames.AUDIT_EXPORT_OBSERVER_DROPPED).meter()
            .shouldNotBeNull().let { it is FunctionCounter }.shouldBeTrue()
        registry.find(MicrometerNames.AUDIT_EXPORT_OBSERVER_REGISTRATION_DROPPED).meter()
            .shouldNotBeNull().let { it is FunctionCounter }.shouldBeTrue()
        registry.find(MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_FAILURES).meter()
            .shouldNotBeNull().let { it is FunctionCounter }.shouldBeTrue()
        registry.get(MicrometerNames.AUDIT_EXPORT_QUEUE_DEPTH).gauge().value() shouldBeEqualTo 2.0
        registry.get(MicrometerNames.AUDIT_EXPORT_IN_FLIGHT).gauge().value() shouldBeEqualTo 3.0
        val rejectionCounter = registry.get(MicrometerNames.AUDIT_EXPORT_REJECTIONS)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "rejected")
            .functionCounter()
        rejectionCounter.count() shouldBeEqualTo 17.0
        exporter.close()
    }

    @Test
    fun `transient snapshot failure keeps the last trusted open values`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 7, queued = 2, inFlight = 1))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val accepted = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
        val diagnosticsClosed = registry.find(MicrometerNames.AUDIT_EXPORT_DIAGNOSTICS_CLOSED)
            .gauge()

        accepted?.count() shouldBeEqualTo 7.0
        diagnosticsClosed?.value() shouldBeEqualTo 0.0

        delegate.failSnapshotsWith(IllegalStateException("transient snapshot failure"))

        repeat(2) { accepted?.count() shouldBeEqualTo 7.0 }
        diagnosticsClosed?.value() shouldBeEqualTo 0.0
        registry.get(MicrometerNames.AUDIT_EXPORT_QUEUE_DEPTH).gauge().value() shouldBeEqualTo 2.0
        registry.get(MicrometerNames.AUDIT_EXPORT_IN_FLIGHT).gauge().value() shouldBeEqualTo 1.0

        delegate.failSnapshotsWith(null)
        exporter.close()
    }

    @Test
    fun `foreign meter replacement is detected during metric reads`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 7))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val owned = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
            .shouldNotBeNull()
        (owned as FunctionCounter).count() shouldBeEqualTo 7.0

        registry.remove(owned)
        registry.counter(
            MicrometerNames.AUDIT_EXPORT_ACCEPTED,
            MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME,
            "accepted",
        )

        owned.count() shouldBeEqualTo 7.0
        exporter.close()
    }

    @Test
    fun `ownership crossing before close preserves the trusted tombstone`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 100))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val owned = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
            .shouldNotBeNull()

        (owned as FunctionCounter).count() shouldBeEqualTo 100.0
        registry.remove(owned)
        registry.counter(
            MicrometerNames.AUDIT_EXPORT_ACCEPTED,
            MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME,
            "accepted",
        )
        owned.count() shouldBeEqualTo 100.0

        delegate.setSnapshot(snapshot(accepted = 200))
        exporter.close()
        owned.count() shouldBeEqualTo 100.0
    }

    @Test
    fun `ownership crossing during close preserves the closing tombstone`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 100))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val owned = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
            .shouldNotBeNull()

        (owned as FunctionCounter).count() shouldBeEqualTo 100.0
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        delegate.setSnapshot(snapshot(accepted = 110))
        delegate.blockClose(closeEntered, closeRelease)
        val closeFinished = CountDownLatch(1)
        Thread {
            exporter.close()
            closeFinished.countDown()
        }.start()
        closeEntered.await(1, TimeUnit.SECONDS).shouldBeTrue()

        registry.remove(owned)
        registry.counter(
            MicrometerNames.AUDIT_EXPORT_ACCEPTED,
            MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME,
            "accepted",
        )
        owned.count() shouldBeEqualTo 110.0

        delegate.setSnapshot(snapshot(accepted = 200))
        closeRelease.countDown()
        closeFinished.await(1, TimeUnit.SECONDS).shouldBeTrue()
        owned.count() shouldBeEqualTo 110.0
    }

    @Test
    fun `foreign meter replacement after close does not double count trusted values`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 100))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val owned = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .meter()
            .shouldNotBeNull()
        (owned as FunctionCounter).count() shouldBeEqualTo 100.0

        exporter.close()
        registry.remove(owned)
        registry.counter(
            MicrometerNames.AUDIT_EXPORT_ACCEPTED,
            MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME,
            "accepted",
        )

        owned.count() shouldBeEqualTo 100.0
    }

    @Test
    fun `fatal snapshot error is rethrown from metric polling`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 7))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val accepted = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
            .shouldNotBeNull()

        delegate.failSnapshotsWith(AssertionError("secret-token"))
        assertFailsWith<AssertionError> { accepted.count() }

        delegate.failSnapshotsWith(null)
        exporter.close()
    }

    @Test
    fun `snapshot cancellation is rethrown from metric polling`() {
        val registry = SimpleMeterRegistry()
        val delegate = SnapshotExporter(snapshot(accepted = 7))
        val exporter = MicrometerLeaderAuditExporter(delegate, registry)
        val accepted = registry.find(MicrometerNames.AUDIT_EXPORT_ACCEPTED)
            .tag(MicrometerNames.AUDIT_EXPORT_TAG_OUTCOME, "accepted")
            .functionCounter()
            .shouldNotBeNull()

        delegate.failSnapshotsWith(CancellationException("cancelled"))
        assertFailsWith<CancellationException> { accepted.count() }

        delegate.failSnapshotsWith(null)
        exporter.close()
    }

    @Test
    fun `java fixture and public decorator descriptors remain stable`() {
        MicrometerLeaderAuditExporterJavaContractTest.exercise().shouldBeTrue()
        MicrometerLeaderAuditExporter::class.java.getConstructor(
            LeaderAuditExporter::class.java,
            io.micrometer.core.instrument.MeterRegistry::class.java,
        )
        MicrometerLeaderAuditExporter::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet() shouldBeEqualTo setOf("submit", "observe", "snapshot", "close")
    }

    private fun event(): LeaderAuditExportEvent.History = LeaderAuditExportEvent.History.from(
        LeaderLockHistoryRecord(
            lockName = "dynamic-lock",
            token = "secret-token",
            kind = LockIdentity.AnnotationKind.SINGLE,
            acquiredAt = Instant.parse("2026-08-19T00:00:00Z"),
            lockedUntil = Instant.parse("2026-08-19T00:01:00Z"),
        ),
        io.bluetape4k.leader.audit.LeaderAuditValueSanitizer.Default,
    )

    private fun snapshot(
        queued: Int = 0,
        inFlight: Int = 0,
        scheduledRetries: Int = 0,
        admitted: Int = 0,
        accepted: Long = 0,
        droppedQueueFull: Long = 0,
        droppedClosed: Long = 0,
        retries: Long = 0,
        terminalFailures: Long = 0,
        cancellations: Long = 0,
        executorRejections: Long = 0,
        schedulerRejections: Long = 0,
        observerDrops: Long = 0,
        observerRegistrationDrops: Long = 0,
        diagnosticsFatalErrors: Long = 0,
        diagnosticsClosed: Boolean = false,
        closed: Boolean = false,
    ): LeaderAuditExportSnapshot {
        val companion = LeaderAuditExportSnapshot::class.java.getDeclaredField("Companion").get(null)
        return createSnapshotMethod.invoke(
            companion,
            queued,
            inFlight,
            scheduledRetries,
            admitted,
            accepted,
            droppedQueueFull,
            droppedClosed,
            retries,
            terminalFailures,
            cancellations,
            executorRejections,
            schedulerRejections,
            observerDrops,
            observerRegistrationDrops,
            diagnosticsFatalErrors,
            diagnosticsClosed,
            closed,
        ) as LeaderAuditExportSnapshot
    }

    private class SnapshotExporter(
        initialSnapshot: LeaderAuditExportSnapshot,
    ) : LeaderAuditExporter {
        private val current = AtomicReference(initialSnapshot)
        private val snapshotFailure = AtomicReference<Throwable?>(null)
        private var scriptedSnapshots: ArrayDeque<() -> LeaderAuditExportSnapshot>? = null
        private var closeFailure: Throwable? = null
        private var submitEntered: CountDownLatch? = null
        private var submitRelease: CountDownLatch? = null
        private var closeEntered: CountDownLatch? = null
        private var closeRelease: CountDownLatch? = null
        private var closed = false
        var closeCount: Int = 0
            private set

        override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult =
            if (closed) {
                LeaderAuditSubmitResult.DROPPED_CLOSED
            } else {
                submitEntered?.countDown()
                submitRelease?.await(5, TimeUnit.SECONDS)
                LeaderAuditSubmitResult.ACCEPTED
            }

        override fun observe(observer: LeaderAuditExportObserver): AutoCloseable = AutoCloseable { }

        override fun snapshot(): LeaderAuditExportSnapshot =
            scriptedSnapshots?.pollFirst()?.invoke()
                ?: snapshotFailure.get()?.let { throw it }
                ?: current.get()

        fun failSnapshotsWith(failure: Throwable?) {
            snapshotFailure.set(failure)
        }

        fun scriptSnapshots(vararg steps: () -> LeaderAuditExportSnapshot) {
            scriptedSnapshots = ArrayDeque(steps.toList())
        }

        fun failCloseWith(failure: Throwable?) {
            closeFailure = failure
        }

        fun setSnapshot(snapshot: LeaderAuditExportSnapshot) {
            current.set(snapshot)
        }

        fun blockSubmissions(entered: CountDownLatch, release: CountDownLatch) {
            submitEntered = entered
            submitRelease = release
        }

        fun blockClose(entered: CountDownLatch, release: CountDownLatch) {
            closeEntered = entered
            closeRelease = release
        }

        override fun close() {
            closeEntered?.countDown()
            closeRelease?.await(5, TimeUnit.SECONDS)
            closeCount++
            closed = true
            closeFailure?.let { throw it }
        }
    }

    private class FailingMeterRegistry(
        private val failOnSuccessfulAttempt: Int,
    ) : SimpleMeterRegistry() {
        private var failRegistration = true
        private val successfulIds = mutableSetOf<Meter.Id>()
        var duplicateRegistrationIds: Int = 0
            private set
        var removeCalls: Int = 0
            private set

        override fun <T : Any> newFunctionCounter(
            id: Meter.Id,
            obj: T,
            countFunction: ToDoubleFunction<T>,
        ): FunctionCounter {
            if (failRegistration && successfulIds.size + 1 == failOnSuccessfulAttempt) {
                throw IllegalStateException("injected meter registration failure")
            }
            if (!successfulIds.add(id)) duplicateRegistrationIds++
            return super.newFunctionCounter(id, obj, countFunction)
        }

        override fun <T : Any> newGauge(
            id: Meter.Id,
            obj: T?,
            valueFunction: ToDoubleFunction<T>,
        ): Gauge {
            if (!successfulIds.add(id)) duplicateRegistrationIds++
            return super.newGauge(id, obj, valueFunction)
        }

        override fun remove(meter: Meter): Meter? {
            removeCalls++
            return super.remove(meter)
        }

        fun allowRegistration() {
            failRegistration = false
        }
    }

    private companion object {
        fun registryManagerStore(): Any {
            val storeClass = Class.forName(
                "${MicrometerLeaderAuditExporter::class.java.name}\$RegistryManagerStore",
            )
            return storeClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        }

        fun privateField(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }

        @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
        fun containsStrongReference(
            root: Any?,
            target: Any,
            visited: IdentityHashMap<Any, Boolean> = IdentityHashMap(),
        ): Boolean {
            if (root === target) return true
            if (root == null || root is java.lang.ref.Reference<*> || root is Class<*> ||
                root is String || root is Number || root is Boolean || root is Char
            ) {
                return false
            }
            if (visited.put(root, true) != null) return false
            when (root) {
                is Map<*, *> -> return root.entries.any { entry ->
                    containsStrongReference(entry.key, target, visited) ||
                        containsStrongReference(entry.value, target, visited)
                }
                is Iterable<*> -> return root.any { containsStrongReference(it, target, visited) }
                is Array<*> -> return root.any { containsStrongReference(it, target, visited) }
            }
            var type: Class<*>? = root.javaClass
            while (type != null && type != Any::class.java && !type.name.startsWith("java.")) {
                type.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .forEach { field ->
                        if (field.trySetAccessible() && containsStrongReference(field.get(root), target, visited)) {
                            return true
                        }
                    }
                type = type.superclass
            }
            return false
        }

        val createSnapshotMethod: Method = run {
            val companion = LeaderAuditExportSnapshot::class.java.getDeclaredField("Companion").type
            companion.getDeclaredMethod(
                "create\$io_github_bluetape4k_leader_bluetape4k_leader_core",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }
    }
}

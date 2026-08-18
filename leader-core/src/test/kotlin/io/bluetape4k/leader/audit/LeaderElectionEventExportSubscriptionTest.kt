package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.LeaderElectionEvent
import io.bluetape4k.leader.LeaderElectionEventPublisher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LeaderElectionEventExportSubscriptionTest {

    @Test
    fun `subscription exports lifecycle events and stops after close`() = runTest {
        val publisher = FakePublisher()
        val exporter = RecordingExporter()
        val subscription = LeaderElectionEventExportSubscription(publisher, this, exporter)

        runCurrent()
        publisher.subject.emit(LeaderElectionEvent.Elected("job", leaderId = "node-1"))
        runCurrent()
        subscription.close()
        publisher.subject.emit(LeaderElectionEvent.Revoked("job"))
        runCurrent()

        exporter.events.size shouldBeEqualTo 1
        (exporter.events.single() as LeaderAuditExportEvent.Lifecycle).outcome
            .shouldBeEqualTo(LeaderAuditLifecycleOutcome.ELECTED)
        subscription.close()
    }

    private class FakePublisher : LeaderElectionEventPublisher {
        val subject = MutableSharedFlow<LeaderElectionEvent>(extraBufferCapacity = 8)
        override val events: kotlinx.coroutines.flow.Flow<LeaderElectionEvent>
            get() = subject
    }

    private class RecordingExporter : LeaderAuditExporter {
        val events = mutableListOf<LeaderAuditExportEvent>()

        override fun submit(event: LeaderAuditExportEvent): LeaderAuditSubmitResult {
            events += event
            return LeaderAuditSubmitResult.ACCEPTED
        }

        override fun observe(observer: LeaderAuditExportObserver): AutoCloseable = AutoCloseable { }

        override fun snapshot(): LeaderAuditExportSnapshot = LeaderAuditExportSnapshot.create(
            queued = 0,
            inFlight = 0,
            scheduledRetries = 0,
            admitted = 0,
            accepted = events.size.toLong(),
            droppedQueueFull = 0,
            droppedClosed = 0,
            retries = 0,
            terminalFailures = 0,
            cancellations = 0,
            executorRejections = 0,
            schedulerRejections = 0,
            observerDrops = 0,
            observerRegistrationDrops = 0,
            diagnosticsFatalErrors = 0,
            diagnosticsClosed = false,
            closed = false,
        )

        override fun close() = Unit
    }
}

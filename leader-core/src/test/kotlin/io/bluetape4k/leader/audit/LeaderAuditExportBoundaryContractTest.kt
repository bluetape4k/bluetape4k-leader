package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.audit.LeaderAuditExportJavaContractFixture
import io.bluetape4k.leader.history.LeaderLockHistoryRecord
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Instant

class LeaderAuditExportBoundaryContractTest {

    @Test
    fun `public event constants and factories keep the bounded ABI`() {
        val eventType = LeaderAuditExportEvent::class.java
        eventType.getField("MAX_ERROR_MESSAGE_BYTES").getInt(null) shouldBeEqualTo 4096
        eventType.getField("MAX_ERROR_TYPE_BYTES").getInt(null) shouldBeEqualTo 128
        eventType.getField("MAX_TEXT_FIELD_BYTES").getInt(null) shouldBeEqualTo 256
        eventType.getField("MAX_ATTRIBUTES").getInt(null) shouldBeEqualTo 32
        eventType.getField("MAX_ATTRIBUTE_KEY_BYTES").getInt(null) shouldBeEqualTo 128
        eventType.getField("MAX_ATTRIBUTE_VALUE_BYTES").getInt(null) shouldBeEqualTo 512
        eventType.getField("MAX_ATTRIBUTES_TOTAL_BYTES").getInt(null) shouldBeEqualTo 8192
        eventType.getField("MAX_INPUT_ATTRIBUTES").getInt(null) shouldBeEqualTo 32
        eventType.getField("MAX_INPUT_ATTRIBUTES_TOTAL_BYTES").getInt(null) shouldBeEqualTo 8192

        LeaderAuditExportEvent.History.Companion::class.java.getMethod(
            "from",
            LeaderLockHistoryRecord::class.java,
            LeaderAuditValueSanitizer::class.java,
        )
        LeaderAuditExportEvent.Lifecycle.Companion::class.java.methods
            .single { it.name == "from" }

        val rawParameterTypes = LeaderAuditExportEvent.History::class.java.declaredConstructors
            .flatMap { it.parameterTypes.asList() }
        listOf(String::class.java, Map::class.java, Instant::class.java)
            .none { it in rawParameterTypes }
            .shouldBeTrue()
    }

    @Test
    fun `exporter options snapshot and observer surfaces have exact descriptors`() {
        val optionsConstructor = LeaderAuditExportOptions::class.java.declaredConstructors.single()
        optionsConstructor.parameterTypes.size shouldBeEqualTo 8
        optionsConstructor.parameterTypes[0] shouldBeEqualTo Int::class.javaPrimitiveType
        optionsConstructor.parameterTypes[3] shouldBeEqualTo java.time.Duration::class.java

        val exporterMethods = LeaderAuditExporter::class.java.declaredMethods
            .map { it.name }
            .toSet()
        exporterMethods shouldBeEqualTo setOf("submit", "observe", "snapshot", "close")

        val snapshotConstructors = LeaderAuditExportSnapshot::class.java.declaredConstructors
        val publicSnapshotConstructors = snapshotConstructors.filter { Modifier.isPublic(it.modifiers) }
        publicSnapshotConstructors.size shouldBeEqualTo 1
        publicSnapshotConstructors.single().parameterTypes.size shouldBeEqualTo 2
        publicSnapshotConstructors.single().isSynthetic.shouldBeTrue()

        val publicSnapshotMethods = LeaderAuditExportSnapshot::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .toSet()
        publicSnapshotMethods shouldBeEqualTo setOf(
            "getQueued",
            "getInFlight",
            "getScheduledRetries",
            "getAdmitted",
            "getAccepted",
            "getDroppedQueueFull",
            "getDroppedClosed",
            "getRetries",
            "getTerminalFailures",
            "getCancellations",
            "getExecutorRejections",
            "getSchedulerRejections",
            "getObserverDrops",
            "getObserverRegistrationDrops",
            "getDiagnosticsFatalErrors",
            "getDiagnosticsClosed",
            "getClosed",
        )
    }

    @Test
    fun `java fixture constructs options and closes exporter`() {
        LeaderAuditExportJavaContractFixture.exercise().shouldBeTrue()
    }
}

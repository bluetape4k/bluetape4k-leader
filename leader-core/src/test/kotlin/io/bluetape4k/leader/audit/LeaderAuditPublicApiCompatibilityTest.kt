package io.bluetape4k.leader.audit

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionEventPublisher
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.history.LeaderHistorySink
import io.bluetape4k.leader.history.SuspendLeaderHistorySink
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.Test

class LeaderAuditPublicApiCompatibilityTest {

    @Test
    fun `audit sink and event subscription constructors remain public`() {
        ExportingLeaderHistorySink::class.java.hasPublicConstructor(
            LeaderHistorySink::class.java,
            LeaderAuditExporter::class.java,
        ).shouldBeTrue()
        ExportingLeaderHistorySink::class.java.hasPublicConstructor(
            LeaderHistorySink::class.java,
            LeaderAuditExporter::class.java,
            LeaderAuditValueSanitizer::class.java,
        ).shouldBeTrue()

        ExportingSuspendLeaderHistorySink::class.java.hasPublicConstructor(
            SuspendLeaderHistorySink::class.java,
            LeaderAuditExporter::class.java,
        ).shouldBeTrue()
        ExportingSuspendLeaderHistorySink::class.java.hasPublicConstructor(
            SuspendLeaderHistorySink::class.java,
            LeaderAuditExporter::class.java,
            LeaderAuditValueSanitizer::class.java,
        ).shouldBeTrue()

        val publisher = LeaderElectionEventPublisher::class.java
        val scope = CoroutineScope::class.java
        val exporter = LeaderAuditExporter::class.java
        LeaderElectionEventExportSubscription::class.java.hasPublicConstructor(publisher, scope, exporter)
            .shouldBeTrue()
        LeaderElectionEventExportSubscription::class.java.hasPublicConstructor(
            publisher,
            scope,
            exporter,
            Map::class.java,
            LeaderAuditValueSanitizer::class.java,
        ).shouldBeTrue()
    }

    @Test
    fun `leader election exception keeps its published direct superclass`() {
        LeaderElectionException::class.java.superclass shouldBeEqualTo RuntimeException::class.java
    }

    @Test
    fun `audit snapshot retains its 1x companion factory JVM name`() {
        val factoryName = "create\$io_github_bluetape4k_leader_bluetape4k_leader_core"

        LeaderAuditExportSnapshot.Companion::class.java.declaredMethods
            .any { method -> method.name == factoryName }
            .shouldBeTrue()
    }
}

private fun Class<*>.hasPublicConstructor(vararg parameterTypes: Class<*>): Boolean =
    constructors.any { constructor -> constructor.parameterTypes.contentEquals(parameterTypes) }

package io.bluetape4k.leader.spring.properties

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.io.lookup
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.javatimes.minutes
import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsProvider
import io.bluetape4k.leader.diagnostics.LocalLeaderBackendDiagnostics
import io.bluetape4k.leader.spring.LeaderProperties
import io.bluetape4k.leader.spring.observability.LeaderAcquisitionFailureWindowAutoConfiguration
import io.bluetape4k.leader.spring.observability.LeaderBackendHealthAutoConfiguration
import io.bluetape4k.leader.spring.observability.LeaderBackendHealthIndicator
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.health.contributor.Status
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.time.Duration
import java.util.*

class LeaderObservabilityPropertiesSerializationTest {

    @Test
    fun `0_5_0 serialized properties restore backend health defaults`() {
        val restored = deserializeLegacyProperties()

        log.debug { "restored: $restored" }
        restored.backendHealth shouldBeEqualTo LeaderBackendHealthProperties()
    }

    @Test
    fun `0_5_0 serialized health restores acquisition failure window default`() {
        val restored = deserializeLegacyProperties()

        log.debug { "restored: $restored" }
        restored.health.acquisitionFailureWindow shouldBeEqualTo 5.minutes()
    }

    @Test
    fun `0_5_0 serialized properties configure health auto configurations without null fields`() {
        val restored = deserializeLegacyProperties()
        val properties = LeaderProperties(observability = restored)

        val acquisitionFailureWindow = LeaderAcquisitionFailureWindowAutoConfiguration()
            .leaderAcquisitionFailureWindow(properties)

        log.debug { "acquisitionFailureWindow=$acquisitionFailureWindow" }
        acquisitionFailureWindow.view().window shouldBeEqualTo 5.minutes()

        val beanFactory = DefaultListableBeanFactory()
            .apply {
                registerSingleton("legacyStateProvider", LegacyDiagnosticsState())
            }
        val backendHealthIndicator = LeaderBackendHealthAutoConfiguration()
            .leaderBackendHealthIndicator(beanFactory, properties)
            .shouldBeInstanceOf<LeaderBackendHealthIndicator>()

        log.debug { "backendHealthIndicator.health()=${backendHealthIndicator.health()}" }
        backendHealthIndicator.health().status shouldBeEqualTo Status.UNKNOWN
    }

    @Test
    fun `current serialized properties keep serial uid and custom values`() {
        val original = LeaderObservabilityProperties(
            enabled = false,
            lockNames = setOf("orders"),
            health = LeaderObservabilityHealthProperties(
                enabled = true,
                leaseWarningThreshold = Duration.ofSeconds(17),
                acquisitionFailureWindow = Duration.ofSeconds(45),
            ),
            backendHealth = LeaderBackendHealthProperties(
                enabled = true,
                timeout = Duration.ofMillis(275),
            ),
        )

        val restored = roundTrip(original)

        restored shouldBeEqualTo original
        LeaderObservabilityProperties::class.lookup().serialVersionUID shouldBeEqualTo 1L
        LeaderObservabilityHealthProperties::class.lookup().serialVersionUID shouldBeEqualTo 1L
    }

    private fun deserializeLegacyProperties(): LeaderObservabilityProperties =
        ObjectInputStream(
            ByteArrayInputStream(
                Base64.getDecoder().decode(LEGACY_0_5_0_SERIALIZED_PROPERTIES),
            ),
        ).use { input ->
            input.readObject().shouldBeInstanceOf<LeaderObservabilityProperties>()
        }

    @Suppress("DEPRECATION")
    private fun <T: Any> roundTrip(value: T): T {
        val bytes = BinarySerializers.Jdk.serialize(value)
        return BinarySerializers.Jdk.deserialize<T>(bytes).shouldNotBeNull()
    }

    private companion object: KLogging() {
        val LEGACY_0_5_0_SERIALIZED_PROPERTIES = """
            rO0ABXNyAERpby5ibHVldGFwZTRrLmxlYWRlci5zcHJpbmcucHJvcGVydGllcy5MZWFkZXJPYnNlcnZhYmlsaXR5UHJvcGVydGllcwAAAAAAAAABAgAFWgAHZW5hYmxlZEwABmhlYWx0aHQATExpby9ibHVldGFwZTRrL2xlYWRlci9zcHJpbmcvcHJvcGVydGllcy9MZWFkZXJPYnNlcnZhYmlsaXR5SGVhbHRoUHJvcGVydGllcztMAAlsb2NrTmFtZXN0AA9MamF2YS91dGlsL1NldDtMABFzdGF0ZVByb3ZpZGVyQmVhbnQAEkxqYXZhL2xhbmcvU3RyaW5nO0wAB3RyYWNpbmd0AEBMaW8vYmx1ZXRhcGU0ay9sZWFkZXIvc3ByaW5nL3Byb3BlcnRpZXMvTGVhZGVyVHJhY2luZ1Byb3BlcnRpZXM7eHABc3IASmlvLmJsdWV0YXBlNGsubGVhZGVyLnNwcmluZy5wcm9wZXJ0aWVzLkxlYWRlck9ic2VydmFiaWxpdHlIZWFsdGhQcm9wZXJ0aWVzAAAAAAAAAAECAAJaAAdlbmFibGVkTAAVbGVhc2VXYXJuaW5nVGhyZXNob2xkdAAUTGphdmEvdGltZS9EdXJhdGlvbjt4cAFzcgANamF2YS50aW1lLlNlcpVdhLobIkiyDAAAeHB3DQEAAAAAAAAAEQAAAAB4c3IAF2phdmEudXRpbC5MaW5rZWRIYXNoU2V02GzXWpXdKh4CAAB4cgARamF2YS51dGlsLkhhc2hTZXS6RIWVlri3NAMAAHhwdwwAAAAEP0AAAAAAAAJ0AAZvcmRlcnN0AAhwYXltZW50c3h0ABNsZWdhY3lTdGF0ZVByb3ZpZGVyc3IAPmlvLmJsdWV0YXBlNGsubGVhZGVyLnNwcmluZy5wcm9wZXJ0aWVzLkxlYWRlclRyYWNpbmdQcm9wZXJ0aWVzAAAAAAAAAAECAARaAAdlbmFibGVkWgAXaW5jbHVkZUV4Y2VwdGlvbkRldGFpbHNaAA9pbmNsdWRlTGVhZGVySWRaAA9pbmNsdWRlTG9ja05hbWV4cAEBAQE=
        """.trimIndent()
    }

    private class LegacyDiagnosticsState: LeaderElectionState, LeaderBackendDiagnosticsProvider {
        override val backendDescriptor = LocalLeaderBackendDiagnostics.backendDescriptor
    }
}

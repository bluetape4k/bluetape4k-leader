package io.bluetape4k.leader.spring.metadata

import com.fasterxml.jackson.databind.ObjectMapper
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.Serializable

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaderConfigurationMetadataTest {

    private val objectMapper = ObjectMapper()

    private val properties: Map<String, MetadataProperty> by lazy {
        val resource = javaClass.classLoader
            .getResource("META-INF/spring/additional-spring-configuration-metadata.json")
            .shouldNotBeNull()
        val root = resource.openStream().use(objectMapper::readTree)

        root.path("properties")
            .elements()
            .asSequence()
            .map {
                val defaultValue = it.path("defaultValue").takeIf { node -> !node.isMissingNode }
                it.path("name").asText() to MetadataProperty(defaultValue = defaultValue?.asText())
            }
            .toMap()
    }

    @Test
    fun `configuration metadata contains diagnostics backend and management properties`() {
        listOf(
            "bluetape4k.leader.diagnostics.enabled",
            "bluetape4k.leader.diagnostics.strict",
            "bluetape4k.leader.diagnostics.include-bean-names",
            "bluetape4k.leader.scheduling.enabled",
            "bluetape4k.leader.scheduling.policies",
            "bluetape4k.leader.scheduling.policies[].selector",
            "bluetape4k.leader.scheduling.policies[].name",
            "bluetape4k.leader.scheduling.policies[].wait-time",
            "bluetape4k.leader.scheduling.policies[].lease-time",
            "bluetape4k.leader.scheduling.policies[].min-lease-time",
            "bluetape4k.leader.scheduling.policies[].bean",
            "bluetape4k.leader.scheduling.policies[].auto-extend",
            "bluetape4k.leader.scheduling.policies[].stream-bounded",
            "bluetape4k.leader.scheduling.policies[].failure-mode",
            "bluetape4k.leader.route-guard.enabled",
            "bluetape4k.leader.route-guard.authority-mode",
            "bluetape4k.leader.route-guard.elector-bean",
            "bluetape4k.leader.route-guard.rejection-status",
            "bluetape4k.leader.observability.state-provider-bean",
            "bluetape4k.leader.observability.backend-health.enabled",
            "bluetape4k.leader.observability.backend-health.timeout",
            "bluetape4k.leader.mongo.single-collection",
            "bluetape4k.leader.etcd.key-prefix",
            "bluetape4k.leader.consul.key-prefix",
            "bluetape4k.leader.dynamodb.table-name",
            "management.endpoints.web.exposure.include",
            "management.endpoint.leaderBackendDiagnostics.enabled",
        ).forEach { propertyName ->
            properties.keys shouldContain propertyName
        }
    }

    @Test
    fun `configuration metadata defaults match property classes`() {
        mapOf(
            "bluetape4k.leader.group.max-leaders" to "2",
            "bluetape4k.leader.mongo.single-collection" to "leader_election",
            "bluetape4k.leader.mongo.group-collection" to "leader_group_election",
            "bluetape4k.leader.dynamodb.retry-delay" to "PT0.05S",
            "bluetape4k.leader.dynamodb.ttl-padding" to "PT1M",
            "bluetape4k.leader.route-guard.enabled" to "false",
            "bluetape4k.leader.route-guard.authority-mode" to "STATE",
            "bluetape4k.leader.route-guard.elector-bean" to "",
            "bluetape4k.leader.route-guard.rejection-status" to "SERVICE_UNAVAILABLE",
            "bluetape4k.leader.scheduling.enabled" to "false",
            "bluetape4k.leader.scheduling.policies[].min-lease-time" to "PT0S",
            "bluetape4k.leader.scheduling.policies[].bean" to "",
            "bluetape4k.leader.scheduling.policies[].auto-extend" to "false",
            "bluetape4k.leader.scheduling.policies[].stream-bounded" to "false",
            "bluetape4k.leader.scheduling.policies[].failure-mode" to "INHERIT",
            "bluetape4k.leader.observability.state-provider-bean" to "",
            "bluetape4k.leader.observability.backend-health.enabled" to "false",
            "bluetape4k.leader.observability.backend-health.timeout" to "PT0.5S",
        ).forEach { (propertyName, defaultValue) ->
            properties[propertyName].shouldNotBeNull().defaultValue shouldBeEqualTo defaultValue
        }
    }

    private data class MetadataProperty(
        val defaultValue: String?,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }
}

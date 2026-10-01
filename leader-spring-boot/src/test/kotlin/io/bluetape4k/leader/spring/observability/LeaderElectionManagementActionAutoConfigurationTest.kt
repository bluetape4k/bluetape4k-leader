package io.bluetape4k.leader.spring.observability

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeSameInstanceAs
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderManagementActionObserver
import io.bluetape4k.leader.LeaderManagementActionRegistry
import io.bluetape4k.leader.spring.properties.LeaderManagementActionProperties
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

class LeaderElectionManagementActionAutoConfigurationTest {

    companion object: KLogging()

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(LeaderElectionManagementActionAutoConfiguration::class.java),
        )

    @Test
    fun `action is disabled by default`() {
        runner.run { context ->
            context.getBeansOfType<LeaderElectionActionWebEndpoint>().shouldBeEmpty()
            context.getBeansOfType<LeaderManagementActionRegistry>().shouldBeEmpty()
        }
    }

    @Test
    fun `nested action remains fail closed when parent endpoint is disabled`() {
        runner
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=false",
                "management.endpoint.leaderElection.actions.enabled=true",
            )
            .run { context ->
                context.getBeansOfType<LeaderElectionActionWebEndpoint>().shouldBeEmpty()
                context.getBeansOfType<LeaderManagementActionRegistry>().shouldBeEmpty()
            }
    }

    @Test
    fun `both parent and nested properties create default registry endpoint and lifecycle`() {
        runner
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "management.endpoint.leaderElection.actions.enabled=true",
            )
            .run { context ->
                context.getBeansOfType<LeaderElectionActionWebEndpoint>().size shouldBeEqualTo 1
                context.getBeansOfType<LeaderManagementActionRegistry>().size shouldBeEqualTo 1
                context.getBeansOfType<LeaderManagementActionLifecycle>().size shouldBeEqualTo 1
                context.getBean<LeaderManagementActionProperties>().timeout shouldBeEqualTo
                        LeaderManagementActionProperties.DEFAULT_TIMEOUT
            }
    }

    @Test
    fun `application registry wins and library lifecycle is not installed`() {
        runner
            .withUserConfiguration(CustomRegistryConfiguration::class.java)
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "management.endpoint.leaderElection.actions.enabled=true",
            )
            .run { context ->
                context.getBeansOfType<LeaderManagementActionRegistry>().size shouldBeEqualTo 1
                context.getBean<LeaderManagementActionRegistry>()
                    .shouldNotBeNull() shouldBeSameInstanceAs
                        context.getBean<LeaderManagementActionRegistry>("customRegistry")

                context.getBeansOfType<LeaderManagementActionLifecycle>().shouldBeEmpty()
                context.getBeansOfType<LeaderElectionActionWebEndpoint>().size shouldBeEqualTo 1
            }
    }

    @Test
    fun `property timeout is positive and bounded`() {
        LeaderManagementActionProperties(timeout = 5.seconds()).timeout shouldBeEqualTo 5.seconds()

        assertFailsWith<IllegalArgumentException> {
            LeaderManagementActionProperties(timeout = Duration.ZERO)
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderManagementActionProperties(timeout = 31.seconds())
        }
    }

    @Test
    fun `default registry does not require optional micrometer classes`() {
        runner
            .withClassLoader(
                FilteredClassLoader(
                    "io.micrometer.core.instrument",
                    "io.bluetape4k.leader.micrometer",
                ),
            )
            .withPropertyValues(
                "management.endpoint.leaderElection.enabled=true",
                "management.endpoint.leaderElection.actions.enabled=true",
            )
            .run { context ->
                context.getBeansOfType<LeaderManagementActionRegistry>().size shouldBeEqualTo 1
                context.getBeansOfType<LeaderManagementActionObserver>().shouldBeEmpty()
            }
    }

    @Configuration(proxyBeanMethods = false)
    class CustomRegistryConfiguration {
        @Bean
        fun customRegistry(): LeaderManagementActionRegistry = LeaderManagementActionRegistry()
    }
}

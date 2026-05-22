package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderGroupElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElectorFactory
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

/**
 * [LeaderAopFactoryAutoConfiguration.DynamoDbFactoryConfig] /
 * [LeaderAopFactoryAutoConfiguration.DynamoDbSuspendFactoryConfig] factory bean registration.
 */
@SpringBootTest(
    classes = [LeaderTestApplication::class, DynamoDbAopFactoryAutoConfigurationTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "bluetape4k.leader.dynamodb.table-name=leader_test",
        "bluetape4k.leader.dynamodb.clock-skew-tolerance=100ms",
    ],
)
@ImportAutoConfiguration(LeaderAopFactoryAutoConfiguration::class)
class DynamoDbAopFactoryAutoConfigurationTest {

    @TestConfiguration
    open class TestConfig {
        @Bean
        fun dynamoDbClient(): DynamoDbClient = mockk(relaxed = true)

        @Bean
        fun dynamoDbAsyncClient(): DynamoDbAsyncClient = mockk(relaxed = true)
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    fun `dynamoDbLeaderElectionFactory 빈이 등록된다`() {
        ctx.getBean("dynamoDbLeaderElectionFactory").shouldBeInstanceOf<DynamoDbLeaderElectorFactory>()
    }

    @Test
    fun `dynamoDbLeaderGroupElectionFactory 빈이 등록된다`() {
        ctx.getBean("dynamoDbLeaderGroupElectionFactory").shouldBeInstanceOf<DynamoDbLeaderGroupElectorFactory>()
    }

    @Test
    fun `dynamoDbSuspendLeaderElectorFactory 빈이 등록된다`() {
        ctx.getBean("dynamoDbSuspendLeaderElectorFactory").shouldBeInstanceOf<DynamoDbSuspendLeaderElectorFactory>()
    }

    @Test
    fun `dynamoDbSuspendLeaderGroupElectorFactory 빈이 등록된다`() {
        ctx.getBean("dynamoDbSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<DynamoDbSuspendLeaderGroupElectorFactory>()
    }

    @Test
    fun `dynamoDb factories implement common factory interfaces`() {
        ctx.getBean("dynamoDbLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
        ctx.getBean("dynamoDbLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
        ctx.getBean("dynamoDbSuspendLeaderElectorFactory").shouldBeInstanceOf<SuspendLeaderElectorFactory>()
        ctx.getBean("dynamoDbSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<SuspendLeaderGroupElectorFactory>()
    }
}

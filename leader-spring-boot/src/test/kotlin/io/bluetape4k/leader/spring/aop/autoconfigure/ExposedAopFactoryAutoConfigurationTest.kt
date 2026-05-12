package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectorFactory
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectorFactory
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElectorFactory
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

/**
 * [LeaderAopFactoryAutoConfiguration.ExposedJdbcFactoryConfig] /
 * [LeaderAopFactoryAutoConfiguration.ExposedR2dbcSuspendFactoryConfig] — Exposed factory 빈 등록 검증.
 *
 * H2 in-memory DB 사용 (testcontainers 불필요).
 * - JDBC: [Database] 빈 제공 → `exposedJdbcLeaderElectionFactory`, `exposedJdbcLeaderGroupElectionFactory`
 * - R2DBC: [R2dbcDatabase] 빈 제공 → `exposedR2dbcSuspendLeaderElectorFactory`, `exposedR2dbcSuspendLeaderGroupElectorFactory`
 */
@SpringBootTest(
    classes = [LeaderTestApplication::class, ExposedAopFactoryAutoConfigurationTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration(LeaderAopFactoryAutoConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedAopFactoryAutoConfigurationTest {

    companion object : KLogging()

    @TestConfiguration
    open class TestConfig {
        @Bean
        fun exposedJdbcDatabase(): Database =
            Database.connect(
                "jdbc:h2:mem:leader-aop-jdbc-${Base58.randomString(8)};DB_CLOSE_DELAY=-1",
                driver = "org.h2.Driver"
            )

        @Bean
        fun exposedR2dbcDatabase(): R2dbcDatabase =
            R2dbcDatabase.connect(
                "r2dbc:h2:mem:///leader-aop-r2dbc-${Base58.randomString(8)};MODE=MySQL;DB_CLOSE_DELAY=-1"
            )
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    fun `exposedJdbcLeaderElectionFactory 빈이 등록된다`() {
        ctx.getBean("exposedJdbcLeaderElectionFactory").shouldBeInstanceOf<ExposedJdbcLeaderElectorFactory>()
    }

    @Test
    fun `exposedJdbcLeaderGroupElectionFactory 빈이 등록된다`() {
        ctx.getBean("exposedJdbcLeaderGroupElectionFactory").shouldBeInstanceOf<ExposedJdbcLeaderGroupElectorFactory>()
    }

    @Test
    fun `exposedR2dbcSuspendLeaderElectorFactory 빈이 등록된다`() {
        ctx.getBean("exposedR2dbcSuspendLeaderElectorFactory").shouldBeInstanceOf<ExposedR2DbcSuspendLeaderElectorFactory>()
    }

    @Test
    fun `exposedR2dbcSuspendLeaderGroupElectorFactory 빈이 등록된다`() {
        ctx.getBean("exposedR2dbcSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<ExposedR2DbcSuspendLeaderGroupElectorFactory>()
    }

    @Test
    fun `exposedJdbcLeaderElectionFactory 는 LeaderElectorFactory 타입`() {
        ctx.getBean("exposedJdbcLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
    }

    @Test
    fun `exposedJdbcLeaderGroupElectionFactory 는 LeaderGroupElectorFactory 타입`() {
        ctx.getBean("exposedJdbcLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
    }

    @Test
    fun `exposedR2dbcSuspendLeaderElectorFactory 는 SuspendLeaderElectorFactory 타입`() {
        ctx.getBean("exposedR2dbcSuspendLeaderElectorFactory").shouldBeInstanceOf<SuspendLeaderElectorFactory>()
    }

    @Test
    fun `exposedR2dbcSuspendLeaderGroupElectorFactory 는 SuspendLeaderGroupElectorFactory 타입`() {
        ctx.getBean("exposedR2dbcSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<SuspendLeaderGroupElectorFactory>()
    }
}

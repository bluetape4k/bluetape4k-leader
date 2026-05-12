package io.bluetape4k.leader.spring.aop.autoconfigure

import io.bluetape4k.leader.LeaderElectorFactory
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.mongodb.MongoLeaderElectorFactory
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectorFactory
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElectorFactory
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElectorFactory
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.utils.ShutdownQueue
import io.bluetape4k.assertions.shouldBeInstanceOf
import org.bson.Document
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

/**
 * [LeaderAopFactoryAutoConfiguration.MongoFactoryConfig] / [LeaderAopFactoryAutoConfiguration.MongoSuspendFactoryConfig]
 * — MongoDB factory 빈 등록 검증.
 *
 * sync [com.mongodb.client.MongoCollection] 과 coroutine [com.mongodb.kotlin.client.coroutine.MongoCollection]
 * 빈을 모두 제공하면 4종 Mongo factory 빈이 등록된다.
 */
@SpringBootTest(
    classes = [LeaderTestApplication::class, MongoAopFactoryAutoConfigurationTest.TestConfig::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ImportAutoConfiguration(LeaderAopFactoryAutoConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoAopFactoryAutoConfigurationTest {

    companion object : KLogging() {
        val mongoServer = MongoDBServer.Launcher.mongoDB

        val mongoClient by lazy {
            MongoDBServer.Launcher.getClient().also {
                ShutdownQueue.register { it.close() }
            }
        }

        val coroutineMongoClient by lazy {
            MongoDBServer.Launcher.getCoroutineClient().also {
                ShutdownQueue.register { it.close() }
            }
        }

        private val db by lazy { mongoClient.getDatabase("leader_aop_autoconfig_test") }
        private val coroutineDb by lazy { coroutineMongoClient.getDatabase("leader_aop_autoconfig_test") }
    }

    @TestConfiguration
    open class TestConfig {
        @Bean(name = ["leaderLockMongoCollection"])
        fun leaderLockMongoCollection(): com.mongodb.client.MongoCollection<Document> =
            db.getCollection(MongoLock.LOCK_COLLECTION_NAME)

        @Bean(name = ["leaderGroupLockMongoCollection"])
        fun leaderGroupLockMongoCollection(): com.mongodb.client.MongoCollection<Document> =
            db.getCollection(MongoLock.GROUP_LOCK_COLLECTION_NAME)

        @Bean(name = ["leaderLockMongoCoroutineCollection"])
        fun leaderLockMongoCoroutineCollection(): com.mongodb.kotlin.client.coroutine.MongoCollection<Document> =
            coroutineDb.getCollection(MongoLock.LOCK_COLLECTION_NAME)

        @Bean(name = ["leaderGroupLockMongoCoroutineCollection"])
        fun leaderGroupLockMongoCoroutineCollection(): com.mongodb.kotlin.client.coroutine.MongoCollection<Document> =
            coroutineDb.getCollection(MongoLock.GROUP_LOCK_COLLECTION_NAME)
    }

    @Autowired
    private lateinit var ctx: ApplicationContext

    @Test
    fun `mongoLeaderElectionFactory 빈이 등록된다`() {
        ctx.getBean("mongoLeaderElectionFactory").shouldBeInstanceOf<MongoLeaderElectorFactory>()
    }

    @Test
    fun `mongoLeaderGroupElectionFactory 빈이 등록된다`() {
        ctx.getBean("mongoLeaderGroupElectionFactory").shouldBeInstanceOf<MongoLeaderGroupElectorFactory>()
    }

    @Test
    fun `mongoSuspendLeaderElectorFactory 빈이 등록된다`() {
        ctx.getBean("mongoSuspendLeaderElectorFactory").shouldBeInstanceOf<MongoSuspendLeaderElectorFactory>()
    }

    @Test
    fun `mongoSuspendLeaderGroupElectorFactory 빈이 등록된다`() {
        ctx.getBean("mongoSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<MongoSuspendLeaderGroupElectorFactory>()
    }

    @Test
    fun `mongoLeaderElectionFactory 는 LeaderElectorFactory 타입`() {
        ctx.getBean("mongoLeaderElectionFactory").shouldBeInstanceOf<LeaderElectorFactory>()
    }

    @Test
    fun `mongoLeaderGroupElectionFactory 는 LeaderGroupElectorFactory 타입`() {
        ctx.getBean("mongoLeaderGroupElectionFactory").shouldBeInstanceOf<LeaderGroupElectorFactory>()
    }

    @Test
    fun `mongoSuspendLeaderElectorFactory 는 SuspendLeaderElectorFactory 타입`() {
        ctx.getBean("mongoSuspendLeaderElectorFactory").shouldBeInstanceOf<SuspendLeaderElectorFactory>()
    }

    @Test
    fun `mongoSuspendLeaderGroupElectorFactory 는 SuspendLeaderGroupElectorFactory 타입`() {
        ctx.getBean("mongoSuspendLeaderGroupElectorFactory").shouldBeInstanceOf<SuspendLeaderGroupElectorFactory>()
    }
}

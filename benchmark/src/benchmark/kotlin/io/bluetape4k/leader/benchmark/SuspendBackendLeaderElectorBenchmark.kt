package io.bluetape4k.leader.benchmark

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.leader.hazelcast.HazelcastSuspendLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import io.bluetape4k.testcontainers.storage.HazelcastServer
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.runBlocking
import org.apache.curator.framework.CuratorFramework
import org.bson.Document
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
open class SuspendBackendLeaderElectorBenchmark {

    @Param("local", "lettuce", "redisson", "exposed-r2dbc-h2", "mongo", "hazelcast", "zookeeper")
    lateinit var backend: String

    private val leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 60.seconds)

    private lateinit var elector: SuspendLeaderElector
    private lateinit var lockName: String
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null
    private var hazelcastClient: HazelcastInstance? = null
    private var curator: CuratorFramework? = null

    @Setup
    fun setup() = runBlocking {
        lockName = "bench-$backend-suspend"
        elector = when (backend) {
            "local" -> LocalSuspendLeaderElector(leaderOptions)
            "lettuce" -> createLettuceElector()
            "redisson" -> createRedissonElector()
            "exposed-r2dbc-h2" -> createExposedR2dbcH2Elector()
            "mongo" -> createMongoElector()
            "hazelcast" -> createHazelcastElector()
            "zookeeper" -> createZooKeeperElector()
            else -> error("Unsupported backend: $backend")
        }
        require(elector.runIfLeader("$lockName-smoke") { true } == true) {
            "Benchmark backend failed suspend leader election smoke check. backend=$backend"
        }
    }

    @TearDown
    fun tearDown() {
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
        closeResource("hazelcastClient") { hazelcastClient?.shutdown() }
        closeResource("curator") { curator?.close() }
    }

    @Benchmark
    fun runIfLeader(blackhole: Blackhole) = runBlocking {
        blackhole.consume(elector.runIfLeader(lockName) { 1 })
    }

    private fun createLettuceElector(): SuspendLeaderElector {
        val redis = RedisServer.Launcher.redis
        val client = RedisClient.create(redis.url)
        val connection = client.connect(StringCodec.UTF8)
        lettuceClient = client
        lettuceConnection = connection
        return LettuceSuspendLeaderElector(connection, leaderOptions)
    }

    private fun createRedissonElector(): SuspendLeaderElector {
        val redis = RedisServer.Launcher.redis
        val client = Redisson.create(
            Config().apply {
                useSingleServer()
                    .setAddress(redis.url)
                    .setConnectionPoolSize(8)
                    .setConnectionMinimumIdleSize(2)
            }
        )
        redissonClient = client
        return RedissonSuspendLeaderElector(client, leaderOptions)
    }

    private suspend fun createExposedR2dbcH2Elector(): SuspendLeaderElector {
        val db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///leader_benchmark_suspend;MODE=MySQL;DB_CLOSE_DELAY=-1",
            user = "",
            password = "",
        )
        return ExposedR2DbcSuspendLeaderElector(
            db,
            ExposedR2dbcLeaderElectionOptions(leaderOptions = leaderOptions),
        )
    }

    private suspend fun createMongoElector(): SuspendLeaderElector {
        val db = MongoDBServer.Launcher.getCoroutineClient().getDatabase("leader_benchmark")
        return MongoSuspendLeaderElector(
            db.getCollection<Document>(MongoLock.LOCK_COLLECTION_NAME),
            MongoLeaderElectionOptions(leaderOptions = leaderOptions),
        )
    }

    private fun createHazelcastElector(): SuspendLeaderElector {
        val server = HazelcastServer.Launcher.hazelcast
        val client = HazelcastClient.newHazelcastClient(
            ClientConfig().apply {
                networkConfig.addAddress(server.url)
            }
        )
        hazelcastClient = client
        return HazelcastSuspendLeaderElector(client, leaderOptions)
    }

    private fun createZooKeeperElector(): SuspendLeaderElector {
        val client = ZooKeeperServer.Launcher.getCuratorFramework(ZooKeeperServer.Launcher.zookeeper).also {
            it.start()
            require(it.blockUntilConnected(30, TimeUnit.SECONDS)) {
                "ZooKeeper benchmark client did not connect within 30 seconds."
            }
        }
        curator = client
        return ZooKeeperSuspendLeaderElector(client, options = leaderOptions)
    }

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure { log.warn("Benchmark resource cleanup failed. resource=$resource, backend=$backend", it) }
    }

    companion object : KLogging()
}

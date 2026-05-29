package io.bluetape4k.leader.benchmark

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElector
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.aws.DynamoDbLocalServer
import io.bluetape4k.testcontainers.database.MySQL8Server
import io.bluetape4k.testcontainers.database.PostgreSQLServer
import io.bluetape4k.testcontainers.infra.ConsulServer
import io.bluetape4k.testcontainers.infra.EtcdServer
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import io.bluetape4k.testcontainers.storage.HazelcastServer
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.etcd.jetcd.Client
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import org.apache.curator.framework.CuratorFramework
import org.jetbrains.exposed.v1.jdbc.Database
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
open class BackendLeaderElectorBenchmark {

    @Param(
        "local",
        "lettuce",
        "redisson",
        "exposed-jdbc-h2",
        "exposed-jdbc-postgresql",
        "exposed-jdbc-mysql",
        "mongo",
        "hazelcast",
        "zookeeper",
        "consul",
        "etcd",
        "dynamodb",
    )
    lateinit var backend: String

    private val leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 60.seconds)

    private lateinit var elector: LeaderElector
    private lateinit var lockName: String
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null
    private var hazelcastClient: HazelcastInstance? = null
    private var curator: CuratorFramework? = null
    private var etcdClient: Client? = null
    private var dynamoDbClient: DynamoDbClient? = null

    @Setup
    fun setup() {
        lockName = "bench-$backend-blocking"
        elector = when (backend) {
            "local" -> LocalLeaderElector(leaderOptions)
            "lettuce" -> createLettuceElector()
            "redisson" -> createRedissonElector()
            "exposed-jdbc-h2" -> createExposedJdbcH2Elector()
            "exposed-jdbc-postgresql" -> createExposedJdbcPostgreSqlElector()
            "exposed-jdbc-mysql" -> createExposedJdbcMySqlElector()
            "mongo" -> createMongoElector()
            "hazelcast" -> createHazelcastElector()
            "zookeeper" -> createZooKeeperElector()
            "consul" -> createConsulElector()
            "etcd" -> createEtcdElector()
            "dynamodb" -> createDynamoDbElector()
            else -> error("Unsupported backend: $backend")
        }
        require(elector.runIfLeader("$lockName-smoke") { true } == true) {
            "Benchmark backend failed leader election smoke check. backend=$backend"
        }
    }

    @TearDown
    fun tearDown() {
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
        closeResource("hazelcastClient") { hazelcastClient?.shutdown() }
        closeResource("curator") { curator?.close() }
        closeResource("etcdClient") { etcdClient?.close() }
        closeResource("dynamoDbClient") { dynamoDbClient?.close() }
    }

    @Benchmark
    fun runIfLeader(blackhole: Blackhole) {
        blackhole.consume(elector.runIfLeader(lockName) { 1 })
    }

    private fun createLettuceElector(): LeaderElector {
        val redis = RedisServer.Launcher.redis
        val client = RedisClient.create(redis.url)
        val connection = client.connect(StringCodec.UTF8)
        lettuceClient = client
        lettuceConnection = connection
        return LettuceLeaderElector(connection, leaderOptions)
    }

    private fun createRedissonElector(): LeaderElector {
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
        return RedissonLeaderElector(client, leaderOptions)
    }

    private fun createExposedJdbcH2Elector(): LeaderElector {
        val db = Database.connect(
            url = "jdbc:h2:mem:leader_benchmark_blocking;MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        return ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(leaderOptions = leaderOptions),
        )
    }

    private fun createExposedJdbcPostgreSqlElector(): LeaderElector {
        val postgres = PostgreSQLServer.Launcher.postgres
        return createExposedJdbcElector(
            url = "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}",
            driver = "org.postgresql.Driver",
            user = postgres.username ?: "",
            password = postgres.password ?: "",
        )
    }

    private fun createExposedJdbcMySqlElector(): LeaderElector {
        val mysql = MySQL8Server.Launcher.mysql
        return createExposedJdbcElector(
            url = "jdbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/${mysql.databaseName}",
            driver = "com.mysql.cj.jdbc.Driver",
            user = mysql.username ?: "",
            password = mysql.password ?: "",
        )
    }

    private fun createExposedJdbcElector(
        url: String,
        driver: String,
        user: String,
        password: String,
    ): LeaderElector {
        val db = Database.connect(url = url, driver = driver, user = user, password = password)
        return ExposedJdbcLeaderElector(
            db,
            ExposedJdbcLeaderElectionOptions(leaderOptions = leaderOptions),
        )
    }

    private fun createMongoElector(): LeaderElector {
        val db = MongoDBServer.Launcher.getClient().getDatabase("leader_benchmark")
        return MongoLeaderElector(
            db.getCollection(MongoLock.LOCK_COLLECTION_NAME),
            MongoLeaderElectionOptions(leaderOptions = leaderOptions),
        )
    }

    private fun createHazelcastElector(): LeaderElector {
        val server = HazelcastServer.Launcher.hazelcast
        val client = HazelcastClient.newHazelcastClient(
            ClientConfig().apply {
                networkConfig.addAddress(server.url)
            }
        )
        hazelcastClient = client
        return HazelcastLeaderElector(client, leaderOptions)
    }

    private fun createZooKeeperElector(): LeaderElector {
        val client = ZooKeeperServer.Launcher.getCuratorFramework(ZooKeeperServer.Launcher.zookeeper).also {
            it.start()
            require(it.blockUntilConnected(30, TimeUnit.SECONDS)) {
                "ZooKeeper benchmark client did not connect within 30 seconds."
            }
        }
        curator = client
        return ZooKeeperLeaderElector(client, options = leaderOptions)
    }

    private fun createConsulElector(): LeaderElector {
        val consul = ConsulServer.Launcher.consul
        return ConsulLeaderElector(
            ConsulEndpoint(consul.url),
            ConsulLeaderElectionOptions(
                keyPrefix = "bluetape4k/leader/benchmark/${Base58.randomString(8)}",
                leaderOptions = leaderOptions,
            ),
        )
    }

    private fun createEtcdElector(): LeaderElector {
        val etcd = EtcdServer.Launcher.etcd
        val client = Client.builder()
            .endpoints(etcd.endpoint)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        etcdClient = client
        return EtcdLeaderElector(
            client,
            EtcdLeaderElectionOptions(
                keyPrefix = "/bluetape4k/leader/benchmark/${Base58.randomString(8)}",
                leaderOptions = leaderOptions,
            ),
        )
    }

    private fun createDynamoDbElector(): LeaderElector {
        val dynamoDb = DynamoDbLocalServer.Launcher.dynamoDb
        val client = DynamoDbClient.builder()
            .endpointOverride(dynamoDb.awsEndpoint)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(dynamoDb.awsAccessKey, dynamoDb.awsSecretKey),
                ),
            )
            .region(Region.of(dynamoDb.regionName))
            .build()
        val tableName = "leader_benchmark_${Base58.randomString(12)}"
        createDynamoDbTable(client, tableName)
        dynamoDbClient = client
        return DynamoDbLeaderElector(
            client,
            DynamoDbLeaderElectionOptions(
                tableName = tableName,
                keyPrefix = "benchmark-${Base58.randomString(8)}",
                leaderOptions = leaderOptions,
            ),
        )
    }

    private fun createDynamoDbTable(client: DynamoDbClient, tableName: String) {
        client.createTable(
            CreateTableRequest.builder()
                .tableName(tableName)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("lockName")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                )
                .keySchema(
                    KeySchemaElement.builder()
                        .attributeName("lockName")
                        .keyType(KeyType.HASH)
                        .build(),
                )
                .build(),
        )
        client.waiter().waitUntilTableExists { it.tableName(tableName) }
        runCatching {
            client.updateTimeToLive {
                it.tableName(tableName)
                    .timeToLiveSpecification(
                        TimeToLiveSpecification.builder()
                            .attributeName("ttl")
                            .enabled(true)
                            .build(),
                    )
            }
        }
    }

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure { log.warn("Benchmark resource cleanup failed. resource=$resource, backend=$backend", it) }
    }

    companion object : KLogging()
}

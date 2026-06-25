package io.bluetape4k.leader.benchmark

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulEndpoint
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulSuspendLeaderElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.dynamodb.DynamoDbLeaderElectionOptions
import io.bluetape4k.leader.dynamodb.DynamoDbSuspendLeaderElector
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdSuspendLeaderElector
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
import io.bluetape4k.logging.warn
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
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
class SuspendBackendLeaderElectorBenchmark {

    @Param(
        "local",
        "lettuce",
        "redisson",
        "exposed-r2dbc-h2",
        "exposed-r2dbc-postgresql",
        "exposed-r2dbc-mysql",
        "mongo",
        "hazelcast",
        "zookeeper",
        "consul",
        "etcd",
        "dynamodb",
    )
    lateinit var backend: String

    private val leaderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 60.seconds)

    private lateinit var elector: SuspendLeaderElector
    private lateinit var lockName: String
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null
    private var hazelcastClient: HazelcastInstance? = null
    private var curator: CuratorFramework? = null
    private var etcdClient: Client? = null
    private var dynamoDbClient: DynamoDbClient? = null
    private var dynamoDbAsyncClient: DynamoDbAsyncClient? = null

    @Setup
    fun setup() = runBlocking {
        lockName = "bench-$backend-suspend"
        elector = when (backend) {
            "local"                    -> LocalSuspendLeaderElector(leaderOptions)
            "lettuce"                  -> createLettuceElector()
            "redisson"                 -> createRedissonElector()
            "exposed-r2dbc-h2"         -> createExposedR2dbcH2Elector()
            "exposed-r2dbc-postgresql" -> createExposedR2dbcPostgreSqlElector()
            "exposed-r2dbc-mysql"      -> createExposedR2dbcMySqlElector()
            "mongo"                    -> createMongoElector()
            "hazelcast"                -> createHazelcastElector()
            "zookeeper"                -> createZooKeeperElector()
            "consul"                   -> createConsulElector()
            "etcd"                     -> createEtcdElector()
            "dynamodb"                 -> createDynamoDbElector()
            else                       -> error("Unsupported backend: $backend")
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
        closeResource("etcdClient") { etcdClient?.close() }
        closeResource("dynamoDbAsyncClient") { dynamoDbAsyncClient?.close() }
        closeResource("dynamoDbClient") { dynamoDbClient?.close() }
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

    private suspend fun createExposedR2dbcPostgreSqlElector(): SuspendLeaderElector {
        val postgres = PostgreSQLServer.Launcher.postgres
        return createExposedR2dbcElector(
            url = "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}",
            user = postgres.username ?: "",
            password = postgres.password ?: "",
        )
    }

    private suspend fun createExposedR2dbcMySqlElector(): SuspendLeaderElector {
        val mysql = MySQL8Server.Launcher.mysql
        return createExposedR2dbcElector(
            url = "r2dbc:mysql://${mysql.host}:${mysql.getMappedPort(3306)}/${mysql.databaseName}",
            user = mysql.username ?: "",
            password = mysql.password ?: "",
        )
    }

    private suspend fun createExposedR2dbcElector(
        url: String,
        user: String,
        password: String,
    ): SuspendLeaderElector {
        val db = R2dbcDatabase.connect(url = url, user = user, password = password)
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

    private fun createConsulElector(): SuspendLeaderElector {
        val consul = ConsulServer.Launcher.consul
        return ConsulSuspendLeaderElector(
            ConsulEndpoint(consul.url),
            ConsulLeaderElectionOptions(
                keyPrefix = "bluetape4k/leader/benchmark/${Base58.randomString(8)}",
                leaderOptions = leaderOptions,
            ),
        )
    }

    private fun createEtcdElector(): SuspendLeaderElector {
        val etcd = EtcdServer.Launcher.etcd
        val client = Client.builder()
            .endpoints(etcd.endpoint)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        etcdClient = client
        return EtcdSuspendLeaderElector(
            client,
            EtcdLeaderElectionOptions(
                keyPrefix = "/bluetape4k/leader/benchmark/${Base58.randomString(8)}",
                leaderOptions = leaderOptions,
            ),
        )
    }

    private fun createDynamoDbElector(): SuspendLeaderElector {
        val dynamoDb = DynamoDbLocalServer.Launcher.dynamoDb
        val credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(dynamoDb.awsAccessKey, dynamoDb.awsSecretKey),
        )
        val region = Region.of(dynamoDb.regionName)
        val syncClient = DynamoDbClient.builder()
            .endpointOverride(dynamoDb.awsEndpoint)
            .credentialsProvider(credentials)
            .region(region)
            .build()
        val asyncClient = DynamoDbAsyncClient.builder()
            .endpointOverride(dynamoDb.awsEndpoint)
            .credentialsProvider(credentials)
            .region(region)
            .build()
        val tableName = "leader_benchmark_${Base58.randomString(12)}"
        createDynamoDbTable(syncClient, tableName)
        dynamoDbClient = syncClient
        dynamoDbAsyncClient = asyncClient
        return DynamoDbSuspendLeaderElector(
            asyncClient,
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
            .onFailure {
                log.warn(it) { "Benchmark resource cleanup failed. resource=$resource, backend=$backend" }
            }
    }

    companion object: KLogging()
}

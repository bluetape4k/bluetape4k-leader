package io.bluetape4k.leader.benchmark

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderGroupElector
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderGroupElector
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderGroupElector
import io.bluetape4k.leader.local.LocalLeaderGroupElector
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderGroupElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderGroupElector
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.redisson.RedissonLeaderGroupElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderGroupElector
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderGroupElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderGroupElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.infra.ZooKeeperServer
import io.bluetape4k.testcontainers.storage.MongoDBServer
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.curator.framework.CuratorFramework
import org.bson.Document
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
class LeaderGroupElectorBenchmark {

    @Param(
        "local",
        "lettuce",
        "redisson",
        "exposed-jdbc-h2",
        "mongo",
        "zookeeper",
    )
    lateinit var backend: String

    @Param("1", "2", "8")
    var maxLeaders: Int = 0

    private lateinit var groupOptions: LeaderGroupElectionOptions
    private lateinit var elector: LeaderGroupElector
    private lateinit var freeLockName: String
    private lateinit var saturatedLockName: String
    private lateinit var mixedLockName: String

    private var saturatedHolders = BlockingSlotHolders.Empty
    private var mixedHolders = BlockingSlotHolders.Empty
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null
    private var curator: CuratorFramework? = null

    @Setup
    fun setup() {
        groupOptions = LeaderGroupElectionOptions(
            maxLeaders = maxLeaders,
            waitTime = 25.milliseconds,
            leaseTime = 30.seconds,
        )
        val suffix = Base58.randomString(8)
        freeLockName = "bench-$backend-group-$maxLeaders-free-$suffix"
        saturatedLockName = "bench-$backend-group-$maxLeaders-saturated-$suffix"
        mixedLockName = "bench-$backend-group-$maxLeaders-mixed-$suffix"
        elector = when (backend) {
            "local" -> LocalLeaderGroupElector(groupOptions)
            "lettuce" -> createLettuceElector()
            "redisson" -> createRedissonElector()
            "exposed-jdbc-h2" -> createExposedJdbcH2Elector(suffix)
            "mongo" -> createMongoElector()
            "zookeeper" -> createZooKeeperElector()
            else -> error("Unsupported backend: $backend")
        }
        require(elector.runIfLeaderResult("$freeLockName-smoke") { true }.isElected) {
            "Benchmark backend failed group election smoke check. backend=$backend, maxLeaders=$maxLeaders"
        }
        saturatedHolders = holdSlots(saturatedLockName, maxLeaders)
        mixedHolders = holdSlots(mixedLockName, mixedHeldSlots)
    }

    @TearDown
    fun tearDown() {
        closeHolders("mixedHolders", mixedHolders)
        closeHolders("saturatedHolders", saturatedHolders)
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
        closeResource("curator") { curator?.close() }
    }

    @Benchmark
    fun freeSlotRunIfLeader(blackhole: Blackhole) {
        blackhole.consume(elector.runIfLeaderResult(freeLockName) { 1 })
    }

    @Benchmark
    fun saturatedSkipRunIfLeader(blackhole: Blackhole) {
        blackhole.consume(elector.runIfLeaderResult(saturatedLockName) { 1 })
    }

    @Benchmark
    fun mixedRunIfLeader(blackhole: Blackhole) {
        blackhole.consume(elector.runIfLeaderResult(mixedLockName) { 1 })
    }

    @Benchmark
    fun activeCountSnapshot(blackhole: Blackhole) {
        blackhole.consume(elector.activeCount(saturatedLockName))
    }

    @Benchmark
    fun stateSnapshot(blackhole: Blackhole) {
        blackhole.consume(elector.state(saturatedLockName))
    }

    private val mixedHeldSlots: Int
        get() = (maxLeaders - 1).coerceAtLeast(0)

    private fun holdSlots(lockName: String, slots: Int): BlockingSlotHolders {
        if (slots == 0) {
            return BlockingSlotHolders.Empty
        }
        val ready = CountDownLatch(slots)
        val release = CountDownLatch(1)
        val futures = (0 until slots).map {
            CompletableFuture.runAsync(
                {
                    val acquired = elector.runIfLeader(lockName) {
                        ready.countDown()
                        release.await()
                        1
                    }
                    require(acquired != null) {
                        "Failed to acquire benchmark group slot. backend=$backend, lockName=$lockName"
                    }
                },
                VirtualThreadExecutor,
            )
        }
        require(ready.await(30, TimeUnit.SECONDS)) {
            "Benchmark group slot holders were not ready. backend=$backend, lockName=$lockName, slots=$slots"
        }
        return BlockingSlotHolders(release, futures)
    }

    private fun createLettuceElector(): LeaderGroupElector {
        val redis = RedisServer.Launcher.redis
        val client = RedisClient.create(redis.url)
        val connection = client.connect(StringCodec.UTF8)
        lettuceClient = client
        lettuceConnection = connection
        return LettuceLeaderGroupElector(connection, groupOptions)
    }

    private fun createRedissonElector(): LeaderGroupElector {
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
        return RedissonLeaderGroupElector(client, groupOptions)
    }

    private fun createExposedJdbcH2Elector(suffix: String): LeaderGroupElector {
        val db = Database.connect(
            url = "jdbc:h2:mem:leader_group_benchmark_blocking_$suffix;MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        return ExposedJdbcLeaderGroupElector(
            db,
            ExposedJdbcLeaderGroupElectionOptions(leaderGroupOptions = groupOptions),
        )
    }

    private fun createMongoElector(): LeaderGroupElector {
        val db = MongoDBServer.Launcher.getClient().getDatabase("leader_group_benchmark")
        return MongoLeaderGroupElector(
            db.getCollection(MongoLock.GROUP_LOCK_COLLECTION_NAME),
            MongoLeaderGroupElectionOptions(leaderGroupOptions = groupOptions),
        )
    }

    private fun createZooKeeperElector(): LeaderGroupElector {
        val client = ZooKeeperServer.Launcher.getCuratorFramework(ZooKeeperServer.Launcher.zookeeper).also {
            it.start()
            require(it.blockUntilConnected(30, TimeUnit.SECONDS)) {
                "ZooKeeper benchmark client did not connect within 30 seconds."
            }
        }
        curator = client
        return ZooKeeperLeaderGroupElector(client, groupOptions)
    }

    private fun closeHolders(resource: String, holders: BlockingSlotHolders) {
        holders.release.countDown()
        holders.futures.forEach { future ->
            try {
                future.get(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn(e) { "Benchmark holder cleanup was interrupted. resource=$resource, backend=$backend" }
            } catch (e: ExecutionException) {
                log.warn(e) { "Benchmark holder cleanup failed. resource=$resource, backend=$backend" }
            } catch (e: TimeoutException) {
                log.warn(e) { "Benchmark holder cleanup timed out. resource=$resource, backend=$backend" }
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

@State(Scope.Benchmark)
class SuspendLeaderGroupElectorBenchmark {

    @Param(
        "local",
        "lettuce",
        "redisson",
        "mongo",
        "zookeeper",
    )
    lateinit var backend: String

    @Param("1", "2", "8")
    var maxLeaders: Int = 0

    private lateinit var groupOptions: LeaderGroupElectionOptions
    private lateinit var elector: SuspendLeaderGroupElector
    private lateinit var holderScope: CoroutineScope
    private lateinit var freeLockName: String
    private lateinit var saturatedLockName: String
    private lateinit var mixedLockName: String

    private var saturatedHolders = SuspendSlotHolders.Empty
    private var mixedHolders = SuspendSlotHolders.Empty
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null
    private var curator: CuratorFramework? = null

    @Setup
    fun setup() = runBlocking {
        groupOptions = LeaderGroupElectionOptions(
            maxLeaders = maxLeaders,
            waitTime = 25.milliseconds,
            leaseTime = 30.seconds,
        )
        holderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val suffix = Base58.randomString(8)
        freeLockName = "bench-$backend-suspend-group-$maxLeaders-free-$suffix"
        saturatedLockName = "bench-$backend-suspend-group-$maxLeaders-saturated-$suffix"
        mixedLockName = "bench-$backend-suspend-group-$maxLeaders-mixed-$suffix"
        elector = when (backend) {
            "local" -> LocalSuspendLeaderGroupElector(groupOptions)
            "lettuce" -> createLettuceElector()
            "redisson" -> createRedissonElector()
            "mongo" -> createMongoElector()
            "zookeeper" -> createZooKeeperElector()
            else -> error("Unsupported backend: $backend")
        }
        require(elector.runIfLeaderResultSuspend("$freeLockName-smoke") { true }.isElected) {
            "Benchmark backend failed suspend group election smoke check. backend=$backend, maxLeaders=$maxLeaders"
        }
        saturatedHolders = holdSlots(saturatedLockName, maxLeaders)
        mixedHolders = holdSlots(mixedLockName, mixedHeldSlots)
    }

    @TearDown
    fun tearDown() {
        runBlocking {
            closeHolders(mixedHolders)
            closeHolders(saturatedHolders)
            holderScope.cancel()
        }
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
        closeResource("curator") { curator?.close() }
    }

    @Benchmark
    fun freeSlotRunIfLeader(blackhole: Blackhole) = runBlocking {
        blackhole.consume(elector.runIfLeaderResultSuspend(freeLockName) { 1 })
    }

    @Benchmark
    fun saturatedSkipRunIfLeader(blackhole: Blackhole) = runBlocking {
        blackhole.consume(elector.runIfLeaderResultSuspend(saturatedLockName) { 1 })
    }

    @Benchmark
    fun mixedRunIfLeader(blackhole: Blackhole) = runBlocking {
        blackhole.consume(elector.runIfLeaderResultSuspend(mixedLockName) { 1 })
    }

    @Benchmark
    fun activeCountSnapshot(blackhole: Blackhole) {
        blackhole.consume(elector.activeCount(saturatedLockName))
    }

    @Benchmark
    fun stateSnapshot(blackhole: Blackhole) {
        blackhole.consume(elector.state(saturatedLockName))
    }

    private val mixedHeldSlots: Int
        get() = (maxLeaders - 1).coerceAtLeast(0)

    private fun holdSlots(lockName: String, slots: Int): SuspendSlotHolders {
        if (slots == 0) {
            return SuspendSlotHolders.Empty
        }
        val ready = CountDownLatch(slots)
        val release = CompletableDeferred<Unit>()
        val jobs = (0 until slots).map {
            holderScope.launch {
                val acquired = elector.runIfLeader(lockName) {
                    ready.countDown()
                    release.await()
                    1
                }
                require(acquired != null) {
                    "Failed to acquire suspend benchmark group slot. backend=$backend, lockName=$lockName"
                }
            }
        }
        require(ready.await(30, TimeUnit.SECONDS)) {
            "Suspend benchmark group slot holders were not ready. backend=$backend, lockName=$lockName, slots=$slots"
        }
        return SuspendSlotHolders(release, jobs)
    }

    private fun createLettuceElector(): SuspendLeaderGroupElector {
        val redis = RedisServer.Launcher.redis
        val client = RedisClient.create(redis.url)
        val connection = client.connect(StringCodec.UTF8)
        lettuceClient = client
        lettuceConnection = connection
        return LettuceSuspendLeaderGroupElector(connection, groupOptions)
    }

    private fun createRedissonElector(): SuspendLeaderGroupElector {
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
        return RedissonSuspendLeaderGroupElector(client, groupOptions)
    }

    private suspend fun createMongoElector(): SuspendLeaderGroupElector {
        val syncDb = MongoDBServer.Launcher.getClient().getDatabase("leader_group_benchmark")
        val coroutineDb = MongoDBServer.Launcher.getCoroutineClient().getDatabase("leader_group_benchmark")
        return MongoSuspendLeaderGroupElector(
            syncDb.getCollection(MongoLock.GROUP_LOCK_COLLECTION_NAME),
            coroutineDb.getCollection<Document>(MongoLock.GROUP_LOCK_COLLECTION_NAME),
            MongoLeaderGroupElectionOptions(leaderGroupOptions = groupOptions),
        )
    }

    private fun createZooKeeperElector(): SuspendLeaderGroupElector {
        val client = ZooKeeperServer.Launcher.getCuratorFramework(ZooKeeperServer.Launcher.zookeeper).also {
            it.start()
            require(it.blockUntilConnected(30, TimeUnit.SECONDS)) {
                "ZooKeeper suspend benchmark client did not connect within 30 seconds."
            }
        }
        curator = client
        return ZooKeeperSuspendLeaderGroupElector(client, groupOptions)
    }

    private suspend fun closeHolders(holders: SuspendSlotHolders) {
        holders.release.complete(Unit)
        holders.jobs.joinAll()
    }

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure {
                log.warn(it) { "Benchmark resource cleanup failed. resource=$resource, backend=$backend" }
            }
    }

    companion object: KLogging()
}

private data class BlockingSlotHolders(
    val release: CountDownLatch,
    val futures: List<CompletableFuture<Void>>,
) {
    companion object {
        val Empty = BlockingSlotHolders(CountDownLatch(0), emptyList())
    }
}

private data class SuspendSlotHolders(
    val release: CompletableDeferred<Unit>,
    val jobs: List<kotlinx.coroutines.Job>,
) {
    companion object {
        val Empty = SuspendSlotHolders(CompletableDeferred(Unit), emptyList())
    }
}

private val LeaderRunResult<*>.isElected: Boolean
    get() = this is LeaderRunResult.Elected<*>

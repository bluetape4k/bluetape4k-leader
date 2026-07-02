package io.bluetape4k.leader.benchmark

import io.bluetape4k.codec.Base58
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionOptions
import io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperLeaderElector
import io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElector
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.apache.curator.framework.CuratorFramework
import org.bson.Document
import org.jetbrains.exposed.v1.jdbc.Database
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
import java.io.Serializable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
class BlockingLeaderContentionElectorBenchmark {

    @Param(
        "lettuce",
        "redisson",
        "exposed-jdbc-h2",
        "mongo",
        "zookeeper",
    )
    lateinit var backend: String

    @Param("2", "8", "32")
    var contenders: Int = 0

    private val holderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 30.seconds)
    private val immediateOptions = LeaderElectionOptions(waitTime = 0.milliseconds, leaseTime = 30.seconds)
    private val positiveWaitOptions = LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 30.seconds)

    private lateinit var holderElector: LeaderElector
    private lateinit var immediateElector: LeaderElector
    private lateinit var positiveWaitElector: LeaderElector
    private lateinit var heldLockName: String
    private lateinit var freeLockNamePrefix: String
    private lateinit var parallelLockNamePrefix: String

    private var holder = BlockingHolder.Empty
    private val sequence = AtomicInteger()
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null
    private var curator: CuratorFramework? = null

    @Setup
    fun setup() {
        val suffix = Base58.randomString(8)
        heldLockName = "bench-$backend-contention-held-$suffix"
        freeLockNamePrefix = "bench-$backend-contention-free-$suffix"
        parallelLockNamePrefix = "bench-$backend-contention-parallel-$suffix"

        val factory = createElectorFactory(suffix)
        holderElector = factory.create(holderOptions)
        immediateElector = factory.create(immediateOptions)
        positiveWaitElector = factory.create(positiveWaitOptions)

        require(positiveWaitElector.runIfLeaderResult("$freeLockNamePrefix-smoke") { true }.isElected) {
            "Benchmark backend failed contention smoke check. backend=$backend"
        }

        holder = holdBlockingLock(heldLockName)
        verifyHeldSkipPath(immediateElector, "immediate")
        verifyHeldSkipPath(positiveWaitElector, "positiveWait")
    }

    @TearDown
    fun tearDown() {
        closeHolder(holder)
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
        closeResource("curator") { curator?.close() }
    }

    @Benchmark
    fun skipWhenHeldImmediate(blackhole: Blackhole) {
        blackhole.consume(immediateElector.runIfLeaderResult(heldLockName) { 1 })
    }

    @Benchmark
    fun skipWhenHeldPositiveWait(blackhole: Blackhole) {
        blackhole.consume(positiveWaitElector.runIfLeaderResult(heldLockName) { 1 })
    }

    @Benchmark
    fun parallelContendersImmediate(blackhole: Blackhole) {
        blackhole.consume(runParallelContenders(immediateElector, nextParallelLockName()))
    }

    @Benchmark
    fun parallelContendersPositiveWait(blackhole: Blackhole) {
        blackhole.consume(runParallelContenders(positiveWaitElector, nextParallelLockName()))
    }

    @Benchmark
    fun mixedAcquireAndSkip(blackhole: Blackhole) {
        val freeResult = positiveWaitElector.runIfLeaderResult(nextFreeLockName()) { 1 }
        val skipResult = positiveWaitElector.runIfLeaderResult(heldLockName) { 1 }
        blackhole.consume(
            ContentionOutcome(
                elected = if (freeResult.isElected) 1 else 0,
                skipped = if (skipResult.isSkipped) 1 else 0,
                actionExecutions = if (freeResult.isElected) 1 else 0,
            )
        )
    }

    private fun createElectorFactory(suffix: String): BlockingElectorFactory =
        when (backend) {
            "lettuce" -> {
                val redis = RedisServer.Launcher.redis
                val client = RedisClient.create(redis.url)
                val connection = client.connect(StringCodec.UTF8)
                lettuceClient = client
                lettuceConnection = connection
                BlockingElectorFactory { options -> LettuceLeaderElector(connection, options) }
            }
            "redisson" -> {
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
                BlockingElectorFactory { options -> RedissonLeaderElector(client, options) }
            }
            "exposed-jdbc-h2" -> {
                val db = Database.connect(
                    url = "jdbc:h2:mem:leader_contention_benchmark_blocking_$suffix;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                )
                BlockingElectorFactory { options ->
                    ExposedJdbcLeaderElector(
                        db,
                        ExposedJdbcLeaderElectionOptions(leaderOptions = options),
                    )
                }
            }
            "mongo" -> {
                val db = MongoDBServer.Launcher.getClient().getDatabase("leader_contention_benchmark")
                val collection = db.getCollection(MongoLock.LOCK_COLLECTION_NAME)
                BlockingElectorFactory { options ->
                    MongoLeaderElector(
                        collection,
                        MongoLeaderElectionOptions(leaderOptions = options),
                    )
                }
            }
            "zookeeper" -> {
                val client = ZooKeeperServer.Launcher.getCuratorFramework(ZooKeeperServer.Launcher.zookeeper).also {
                    it.start()
                    require(it.blockUntilConnected(30, TimeUnit.SECONDS)) {
                        "ZooKeeper contention benchmark client did not connect within 30 seconds."
                    }
                }
                curator = client
                BlockingElectorFactory { options -> ZooKeeperLeaderElector(client, options = options) }
            }
            else -> error("Unsupported backend: $backend")
        }

    private fun holdBlockingLock(lockName: String): BlockingHolder {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val future = CompletableFuture.runAsync(
            {
                val acquired = holderElector.runIfLeader(lockName) {
                    ready.countDown()
                    release.await()
                    1
                }
                require(acquired != null) {
                    "Failed to acquire contention holder. backend=$backend, lockName=$lockName"
                }
            },
            VirtualThreadExecutor,
        )
        require(ready.await(30, TimeUnit.SECONDS)) {
            "Contention holder was not ready. backend=$backend, lockName=$lockName"
        }
        return BlockingHolder(release, future)
    }

    private fun verifyHeldSkipPath(elector: LeaderElector, mode: String) {
        var actionExecutions = 0
        val result = elector.runIfLeaderResult(heldLockName) {
            actionExecutions += 1
            true
        }
        require(result.isSkipped && actionExecutions == 0) {
            "Expected held-lock skip path. backend=$backend, mode=$mode, result=$result, actionExecutions=$actionExecutions"
        }
    }

    private fun runParallelContenders(elector: LeaderElector, lockName: String): ContentionOutcome {
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(contenders)
        val winnerEntered = CountDownLatch(1)
        val skipped = CountDownLatch(contenders - 1)
        val releaseWinner = CountDownLatch(1)
        val actionExecutions = AtomicInteger()

        val futures = (0 until contenders).map {
            CompletableFuture.supplyAsync(
                {
                    start.await()
                    attempted.countDown()
                    val result = elector.runIfLeaderResult(lockName) {
                        actionExecutions.incrementAndGet()
                        winnerEntered.countDown()
                        require(attempted.await(30, TimeUnit.SECONDS)) {
                            "Parallel contention contenders did not all attempt. backend=$backend, lockName=$lockName"
                        }
                        releaseWinner.await()
                        1
                    }
                    if (result.isSkipped) {
                        skipped.countDown()
                    }
                    result
                },
                VirtualThreadExecutor,
            )
        }

        start.countDown()
        require(winnerEntered.await(30, TimeUnit.SECONDS)) {
            "No contention winner entered action. backend=$backend, contenders=$contenders"
        }
        require(skipped.await(30, TimeUnit.SECONDS)) {
            "Contention skip paths did not finish. backend=$backend, contenders=$contenders"
        }
        releaseWinner.countDown()

        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        return ContentionOutcome(
            elected = results.count { it.isElected },
            skipped = results.count { it.isSkipped },
            actionExecutions = actionExecutions.get(),
        )
    }

    private fun nextFreeLockName(): String = "$freeLockNamePrefix-${sequence.incrementAndGet()}"

    private fun nextParallelLockName(): String = "$parallelLockNamePrefix-${sequence.incrementAndGet()}"

    private fun closeHolder(holder: BlockingHolder) {
        holder.release.countDown()
        try {
            holder.future.get(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "Contention holder cleanup was interrupted. backend=$backend" }
        } catch (e: ExecutionException) {
            log.warn(e) { "Contention holder cleanup failed. backend=$backend" }
        } catch (e: TimeoutException) {
            log.warn(e) { "Contention holder cleanup timed out. backend=$backend" }
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
class SuspendLeaderContentionElectorBenchmark {

    @Param(
        "lettuce",
        "redisson",
        "exposed-r2dbc-h2",
        "mongo",
        "zookeeper",
    )
    lateinit var backend: String

    @Param("2", "8", "32")
    var contenders: Int = 0

    private val holderOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 30.seconds)
    private val immediateOptions = LeaderElectionOptions(waitTime = 0.milliseconds, leaseTime = 30.seconds)
    private val positiveWaitOptions = LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 30.seconds)

    private lateinit var holderElector: SuspendLeaderElector
    private lateinit var immediateElector: SuspendLeaderElector
    private lateinit var positiveWaitElector: SuspendLeaderElector
    private lateinit var holderScope: CoroutineScope
    private lateinit var heldLockName: String
    private lateinit var freeLockNamePrefix: String
    private lateinit var parallelLockNamePrefix: String

    private var holder = SuspendHolder.Empty
    private val sequence = AtomicInteger()
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null
    private var curator: CuratorFramework? = null

    @Setup
    fun setup() = runBlocking {
        holderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val suffix = Base58.randomString(8)
        heldLockName = "bench-$backend-suspend-contention-held-$suffix"
        freeLockNamePrefix = "bench-$backend-suspend-contention-free-$suffix"
        parallelLockNamePrefix = "bench-$backend-suspend-contention-parallel-$suffix"

        val factory = createElectorFactory(suffix)
        holderElector = factory.create(holderOptions)
        immediateElector = factory.create(immediateOptions)
        positiveWaitElector = factory.create(positiveWaitOptions)

        require(positiveWaitElector.runIfLeaderResultSuspend("$freeLockNamePrefix-smoke") { true }.isElected) {
            "Benchmark backend failed suspend contention smoke check. backend=$backend"
        }

        holder = holdSuspendLock(heldLockName)
        verifyHeldSkipPath(immediateElector, "immediate")
        verifyHeldSkipPath(positiveWaitElector, "positiveWait")
    }

    @TearDown
    fun tearDown() {
        runBlocking {
            closeHolder(holder)
            holderScope.cancel()
        }
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
        closeResource("curator") { curator?.close() }
    }

    @Benchmark
    fun skipWhenHeldImmediate(blackhole: Blackhole) = runBlocking {
        blackhole.consume(immediateElector.runIfLeaderResultSuspend(heldLockName) { 1 })
    }

    @Benchmark
    fun skipWhenHeldPositiveWait(blackhole: Blackhole) = runBlocking {
        blackhole.consume(positiveWaitElector.runIfLeaderResultSuspend(heldLockName) { 1 })
    }

    @Benchmark
    fun parallelContendersPositiveWait(blackhole: Blackhole) = runBlocking {
        blackhole.consume(runParallelContenders(positiveWaitElector, nextParallelLockName()))
    }

    @Benchmark
    fun mixedAcquireAndSkip(blackhole: Blackhole) = runBlocking {
        val freeResult = positiveWaitElector.runIfLeaderResultSuspend(nextFreeLockName()) { 1 }
        val skipResult = positiveWaitElector.runIfLeaderResultSuspend(heldLockName) { 1 }
        blackhole.consume(
            ContentionOutcome(
                elected = if (freeResult.isElected) 1 else 0,
                skipped = if (skipResult.isSkipped) 1 else 0,
                actionExecutions = if (freeResult.isElected) 1 else 0,
            )
        )
    }

    private suspend fun createElectorFactory(suffix: String): SuspendElectorFactory =
        when (backend) {
            "lettuce" -> {
                val redis = RedisServer.Launcher.redis
                val client = RedisClient.create(redis.url)
                val connection = client.connect(StringCodec.UTF8)
                lettuceClient = client
                lettuceConnection = connection
                SuspendElectorFactory { options -> LettuceSuspendLeaderElector(connection, options) }
            }
            "redisson" -> {
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
                SuspendElectorFactory { options -> RedissonSuspendLeaderElector(client, options) }
            }
            "exposed-r2dbc-h2" -> {
                val db = R2dbcDatabase.connect(
                    url = "r2dbc:h2:mem:///leader_contention_benchmark_suspend_$suffix;MODE=MySQL;DB_CLOSE_DELAY=-1",
                    user = "",
                    password = "",
                )
                SuspendElectorFactory { options ->
                    ExposedR2DbcSuspendLeaderElector(
                        db,
                        ExposedR2dbcLeaderElectionOptions(leaderOptions = options),
                    )
                }
            }
            "mongo" -> {
                val db = MongoDBServer.Launcher.getCoroutineClient().getDatabase("leader_contention_benchmark")
                val collection = db.getCollection<Document>(MongoLock.LOCK_COLLECTION_NAME)
                SuspendElectorFactory { options ->
                    MongoSuspendLeaderElector(
                        collection,
                        MongoLeaderElectionOptions(leaderOptions = options),
                    )
                }
            }
            "zookeeper" -> {
                val client = ZooKeeperServer.Launcher.getCuratorFramework(ZooKeeperServer.Launcher.zookeeper).also {
                    it.start()
                    require(it.blockUntilConnected(30, TimeUnit.SECONDS)) {
                        "ZooKeeper suspend contention benchmark client did not connect within 30 seconds."
                    }
                }
                curator = client
                SuspendElectorFactory { options -> ZooKeeperSuspendLeaderElector(client, options = options) }
            }
            else -> error("Unsupported backend: $backend")
        }

    private fun holdSuspendLock(lockName: String): SuspendHolder {
        val ready = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val job = holderScope.async {
            val acquired = holderElector.runIfLeader(lockName) {
                ready.countDown()
                release.await()
                1
            }
            require(acquired != null) {
                "Failed to acquire suspend contention holder. backend=$backend, lockName=$lockName"
            }
        }
        require(ready.await(30, TimeUnit.SECONDS)) {
            "Suspend contention holder was not ready. backend=$backend, lockName=$lockName"
        }
        return SuspendHolder(release, job)
    }

    private suspend fun verifyHeldSkipPath(elector: SuspendLeaderElector, mode: String) {
        var actionExecutions = 0
        val result = elector.runIfLeaderResultSuspend(heldLockName) {
            actionExecutions += 1
            true
        }
        require(result.isSkipped && actionExecutions == 0) {
            "Expected suspend held-lock skip path. backend=$backend, mode=$mode, result=$result, actionExecutions=$actionExecutions"
        }
    }

    private suspend fun runParallelContenders(elector: SuspendLeaderElector, lockName: String): ContentionOutcome {
        val start = CompletableDeferred<Unit>()
        val attempted = CountDownLatch(contenders)
        val winnerEntered = CountDownLatch(1)
        val skipped = CountDownLatch(contenders - 1)
        val releaseWinner = CompletableDeferred<Unit>()
        val actionExecutions = AtomicInteger()

        val jobs = (0 until contenders).map {
            holderScope.async {
                start.await()
                attempted.countDown()
                val result = elector.runIfLeaderResultSuspend(lockName) {
                    actionExecutions.incrementAndGet()
                    winnerEntered.countDown()
                    require(attempted.await(30, TimeUnit.SECONDS)) {
                        "Suspend parallel contenders did not all attempt. backend=$backend, lockName=$lockName"
                    }
                    releaseWinner.await()
                    1
                }
                if (result.isSkipped) {
                    skipped.countDown()
                }
                result
            }
        }

        start.complete(Unit)
        require(winnerEntered.await(30, TimeUnit.SECONDS)) {
            "No suspend contention winner entered action. backend=$backend, contenders=$contenders"
        }
        require(skipped.await(30, TimeUnit.SECONDS)) {
            "Suspend contention skip paths did not finish. backend=$backend, contenders=$contenders"
        }
        releaseWinner.complete(Unit)

        val results = jobs.awaitAll()
        return ContentionOutcome(
            elected = results.count { it.isElected },
            skipped = results.count { it.isSkipped },
            actionExecutions = actionExecutions.get(),
        )
    }

    private fun nextFreeLockName(): String = "$freeLockNamePrefix-${sequence.incrementAndGet()}"

    private fun nextParallelLockName(): String = "$parallelLockNamePrefix-${sequence.incrementAndGet()}"

    private suspend fun closeHolder(holder: SuspendHolder) {
        holder.release.complete(Unit)
        holder.job.await()
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
class LocalBlockingLeaderContentionElectorBenchmark {

    @Param("2", "8", "32")
    var contenders: Int = 0

    private val immediateOptions = LeaderElectionOptions(waitTime = 0.milliseconds, leaseTime = 30.seconds)
    private val positiveWaitOptions = LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 30.seconds)

    private lateinit var immediateElector: LeaderElector
    private lateinit var positiveWaitElector: LeaderElector
    private lateinit var immediateHeldLockName: String
    private lateinit var positiveHeldLockName: String
    private lateinit var freeLockNamePrefix: String
    private lateinit var parallelLockNamePrefix: String

    private var immediateHolder = BlockingHolder.Empty
    private var positiveHolder = BlockingHolder.Empty
    private val sequence = AtomicInteger()

    @Setup
    fun setup() {
        val suffix = Base58.randomString(8)
        immediateHeldLockName = "bench-local-contention-held-immediate-$suffix"
        positiveHeldLockName = "bench-local-contention-held-positive-$suffix"
        freeLockNamePrefix = "bench-local-contention-free-$suffix"
        parallelLockNamePrefix = "bench-local-contention-parallel-$suffix"
        immediateElector = LocalLeaderElector(immediateOptions)
        positiveWaitElector = LocalLeaderElector(positiveWaitOptions)

        require(positiveWaitElector.runIfLeaderResult("$freeLockNamePrefix-smoke") { true }.isElected) {
            "Local blocking contention smoke check failed."
        }
        immediateHolder = holdBlockingLock(immediateElector, immediateHeldLockName)
        positiveHolder = holdBlockingLock(positiveWaitElector, positiveHeldLockName)
        verifyHeldSkipPath(immediateElector, immediateHeldLockName, "immediate")
        verifyHeldSkipPath(positiveWaitElector, positiveHeldLockName, "positiveWait")
    }

    @TearDown
    fun tearDown() {
        closeHolder("immediateHolder", immediateHolder)
        closeHolder("positiveHolder", positiveHolder)
    }

    @Benchmark
    fun skipWhenHeldImmediate(blackhole: Blackhole) {
        blackhole.consume(immediateElector.runIfLeaderResult(immediateHeldLockName) { 1 })
    }

    @Benchmark
    fun skipWhenHeldPositiveWait(blackhole: Blackhole) {
        blackhole.consume(positiveWaitElector.runIfLeaderResult(positiveHeldLockName) { 1 })
    }

    @Benchmark
    fun parallelContendersPositiveWait(blackhole: Blackhole) {
        blackhole.consume(runParallelContenders(positiveWaitElector, nextParallelLockName()))
    }

    @Benchmark
    fun mixedAcquireAndSkip(blackhole: Blackhole) {
        val freeResult = positiveWaitElector.runIfLeaderResult(nextFreeLockName()) { 1 }
        val skipResult = positiveWaitElector.runIfLeaderResult(positiveHeldLockName) { 1 }
        blackhole.consume(
            ContentionOutcome(
                elected = if (freeResult.isElected) 1 else 0,
                skipped = if (skipResult.isSkipped) 1 else 0,
                actionExecutions = if (freeResult.isElected) 1 else 0,
            )
        )
    }

    private fun holdBlockingLock(elector: LeaderElector, lockName: String): BlockingHolder {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val future = CompletableFuture.runAsync(
            {
                val acquired = elector.runIfLeader(lockName) {
                    ready.countDown()
                    release.await()
                    1
                }
                require(acquired != null) {
                    "Failed to acquire local contention holder. lockName=$lockName"
                }
            },
            VirtualThreadExecutor,
        )
        require(ready.await(30, TimeUnit.SECONDS)) {
            "Local contention holder was not ready. lockName=$lockName"
        }
        return BlockingHolder(release, future)
    }

    private fun verifyHeldSkipPath(elector: LeaderElector, lockName: String, mode: String) {
        var actionExecutions = 0
        val result = elector.runIfLeaderResult(lockName) {
            actionExecutions += 1
            true
        }
        require(result.isSkipped && actionExecutions == 0) {
            "Expected local held-lock skip path. mode=$mode, result=$result, actionExecutions=$actionExecutions"
        }
    }

    private fun runParallelContenders(elector: LeaderElector, lockName: String): ContentionOutcome {
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(contenders)
        val winnerEntered = CountDownLatch(1)
        val skipped = CountDownLatch(contenders - 1)
        val releaseWinner = CountDownLatch(1)
        val actionExecutions = AtomicInteger()

        val futures = (0 until contenders).map {
            CompletableFuture.supplyAsync(
                {
                    start.await()
                    attempted.countDown()
                    val result = elector.runIfLeaderResult(lockName) {
                        actionExecutions.incrementAndGet()
                        winnerEntered.countDown()
                        require(attempted.await(30, TimeUnit.SECONDS)) {
                            "Local parallel contenders did not all attempt. lockName=$lockName"
                        }
                        releaseWinner.await()
                        1
                    }
                    if (result.isSkipped) {
                        skipped.countDown()
                    }
                    result
                },
                VirtualThreadExecutor,
            )
        }

        start.countDown()
        require(winnerEntered.await(30, TimeUnit.SECONDS)) {
            "No local contention winner entered action. contenders=$contenders"
        }
        require(skipped.await(30, TimeUnit.SECONDS)) {
            "Local contention skip paths did not finish. contenders=$contenders"
        }
        releaseWinner.countDown()

        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        return ContentionOutcome(
            elected = results.count { it.isElected },
            skipped = results.count { it.isSkipped },
            actionExecutions = actionExecutions.get(),
        )
    }

    private fun nextFreeLockName(): String = "$freeLockNamePrefix-${sequence.incrementAndGet()}"

    private fun nextParallelLockName(): String = "$parallelLockNamePrefix-${sequence.incrementAndGet()}"

    private fun closeHolder(resource: String, holder: BlockingHolder) {
        holder.release.countDown()
        try {
            holder.future.get(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn(e) { "Local contention holder cleanup was interrupted. resource=$resource" }
        } catch (e: ExecutionException) {
            log.warn(e) { "Local contention holder cleanup failed. resource=$resource" }
        } catch (e: TimeoutException) {
            log.warn(e) { "Local contention holder cleanup timed out. resource=$resource" }
        }
    }

    companion object: KLogging()
}

@State(Scope.Benchmark)
class LocalSuspendLeaderContentionElectorBenchmark {

    @Param("2", "8", "32")
    var contenders: Int = 0

    private val positiveWaitOptions = LeaderElectionOptions(waitTime = 25.milliseconds, leaseTime = 30.seconds)

    private lateinit var elector: SuspendLeaderElector
    private lateinit var holderScope: CoroutineScope
    private lateinit var heldLockName: String
    private lateinit var freeLockNamePrefix: String
    private lateinit var parallelLockNamePrefix: String

    private var holder = SuspendHolder.Empty
    private val sequence = AtomicInteger()

    @Setup
    fun setup() = runBlocking {
        val suffix = Base58.randomString(8)
        holderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        heldLockName = "bench-local-suspend-contention-held-$suffix"
        freeLockNamePrefix = "bench-local-suspend-contention-free-$suffix"
        parallelLockNamePrefix = "bench-local-suspend-contention-parallel-$suffix"
        elector = LocalSuspendLeaderElector(positiveWaitOptions)

        require(elector.runIfLeaderResultSuspend("$freeLockNamePrefix-smoke") { true }.isElected) {
            "Local suspend contention smoke check failed."
        }
        holder = holdSuspendLock(heldLockName)
        verifyHeldSkipPath()
    }

    @TearDown
    fun tearDown() {
        runBlocking {
            closeHolder(holder)
            holderScope.cancel()
        }
    }

    @Benchmark
    fun skipWhenHeldPositiveWait(blackhole: Blackhole) = runBlocking {
        blackhole.consume(elector.runIfLeaderResultSuspend(heldLockName) { 1 })
    }

    @Benchmark
    fun parallelContendersPositiveWait(blackhole: Blackhole) = runBlocking {
        blackhole.consume(runParallelContenders(nextParallelLockName()))
    }

    @Benchmark
    fun mixedAcquireAndSkip(blackhole: Blackhole) = runBlocking {
        val freeResult = elector.runIfLeaderResultSuspend(nextFreeLockName()) { 1 }
        val skipResult = elector.runIfLeaderResultSuspend(heldLockName) { 1 }
        blackhole.consume(
            ContentionOutcome(
                elected = if (freeResult.isElected) 1 else 0,
                skipped = if (skipResult.isSkipped) 1 else 0,
                actionExecutions = if (freeResult.isElected) 1 else 0,
            )
        )
    }

    private fun holdSuspendLock(lockName: String): SuspendHolder {
        val ready = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val job = holderScope.async {
            val acquired = elector.runIfLeader(lockName) {
                ready.countDown()
                release.await()
                1
            }
            require(acquired != null) {
                "Failed to acquire local suspend contention holder. lockName=$lockName"
            }
        }
        require(ready.await(30, TimeUnit.SECONDS)) {
            "Local suspend contention holder was not ready. lockName=$lockName"
        }
        return SuspendHolder(release, job)
    }

    private suspend fun verifyHeldSkipPath() {
        var actionExecutions = 0
        val result = elector.runIfLeaderResultSuspend(heldLockName) {
            actionExecutions += 1
            true
        }
        require(result.isSkipped && actionExecutions == 0) {
            "Expected local suspend held-lock skip path. result=$result, actionExecutions=$actionExecutions"
        }
    }

    private suspend fun runParallelContenders(lockName: String): ContentionOutcome {
        val start = CompletableDeferred<Unit>()
        val attempted = CountDownLatch(contenders)
        val winnerEntered = CountDownLatch(1)
        val skipped = CountDownLatch(contenders - 1)
        val releaseWinner = CompletableDeferred<Unit>()
        val actionExecutions = AtomicInteger()

        val jobs = (0 until contenders).map {
            holderScope.async {
                start.await()
                attempted.countDown()
                val result = elector.runIfLeaderResultSuspend(lockName) {
                    actionExecutions.incrementAndGet()
                    winnerEntered.countDown()
                    require(attempted.await(30, TimeUnit.SECONDS)) {
                        "Local suspend parallel contenders did not all attempt. lockName=$lockName"
                    }
                    releaseWinner.await()
                    1
                }
                if (result.isSkipped) {
                    skipped.countDown()
                }
                result
            }
        }

        start.complete(Unit)
        require(winnerEntered.await(30, TimeUnit.SECONDS)) {
            "No local suspend contention winner entered action. contenders=$contenders"
        }
        require(skipped.await(30, TimeUnit.SECONDS)) {
            "Local suspend contention skip paths did not finish. contenders=$contenders"
        }
        releaseWinner.complete(Unit)

        val results = jobs.awaitAll()
        return ContentionOutcome(
            elected = results.count { it.isElected },
            skipped = results.count { it.isSkipped },
            actionExecutions = actionExecutions.get(),
        )
    }

    private fun nextFreeLockName(): String = "$freeLockNamePrefix-${sequence.incrementAndGet()}"

    private fun nextParallelLockName(): String = "$parallelLockNamePrefix-${sequence.incrementAndGet()}"

    private suspend fun closeHolder(holder: SuspendHolder) {
        holder.release.complete(Unit)
        holder.job.await()
    }
}

private fun interface BlockingElectorFactory {
    fun create(options: LeaderElectionOptions): LeaderElector
}

private fun interface SuspendElectorFactory {
    suspend fun create(options: LeaderElectionOptions): SuspendLeaderElector
}

private data class BlockingHolder(
    val release: CountDownLatch,
    val future: CompletableFuture<Void>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 3019430824624016201L

        val Empty = BlockingHolder(CountDownLatch(0), CompletableFuture.completedFuture(null))
    }
}

private data class SuspendHolder(
    val release: CompletableDeferred<Unit>,
    val job: kotlinx.coroutines.Deferred<Unit>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = -8433527060337158541L

        val Empty = SuspendHolder(CompletableDeferred(Unit), CompletableDeferred(Unit))
    }
}

private data class ContentionOutcome(
    val elected: Int,
    val skipped: Int,
    val actionExecutions: Int,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = -5808360179162951240L
    }
}

private val LeaderRunResult<*>.isElected: Boolean
    get() = this is LeaderRunResult.Elected<*>

private val LeaderRunResult<*>.isSkipped: Boolean
    get() = this is LeaderRunResult.Skipped

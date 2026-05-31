package io.bluetape4k.leader.benchmark

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.lettuce.LettuceLeaderElector
import io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElector
import io.bluetape4k.leader.redisson.RedissonLeaderElector
import io.bluetape4k.leader.redisson.RedissonSuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
open class RedisLeaseExtensionBenchmark {

    @Param(
        "lettuce-normal",
        "lettuce-auto-extend",
        "redisson-normal",
        "redisson-auto-extend",
    )
    lateinit var mode: String

    private lateinit var redisMode: RedisLeaseExtensionMode
    private lateinit var quickElector: LeaderElector
    private lateinit var renewalElector: LeaderElector
    private lateinit var quickLockName: String
    private lateinit var renewalLockName: String
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null

    @Setup
    fun setup() {
        redisMode = RedisLeaseExtensionMode.from(mode)
        quickLockName = newLockName("blocking-quick")
        renewalLockName = newLockName("blocking-renewal")

        when (redisMode.backend) {
            RedisLeaseBackend.LETTUCE -> {
                val redis = RedisServer.Launcher.redis
                val client = RedisClient.create(redis.url)
                val connection = client.connect(StringCodec.UTF8)
                lettuceClient = client
                lettuceConnection = connection
                quickElector = LettuceLeaderElector(connection, redisMode.quickOptions())
                renewalElector = LettuceLeaderElector(connection, redisMode.renewalOptions())
            }
            RedisLeaseBackend.REDISSON -> {
                val client = createRedissonClient()
                redissonClient = client
                quickElector = RedissonLeaderElector(client, redisMode.quickOptions())
                renewalElector = RedissonLeaderElector(client, redisMode.renewalOptions())
            }
        }

        require(quickElector.runIfLeader("$quickLockName-smoke") { true } == true) {
            "Redis lease benchmark failed blocking quick smoke check. mode=$mode"
        }
        require(renewalElector.runIfLeader("$renewalLockName-smoke") { true } == true) {
            "Redis lease benchmark failed blocking renewal smoke check. mode=$mode"
        }
    }

    @TearDown
    fun tearDown() {
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
    }

    @Benchmark
    fun runIfLeader(blackhole: Blackhole) {
        blackhole.consume(quickElector.runIfLeader(quickLockName) { 1 })
    }

    @Benchmark
    fun runIfLeaderWithRenewalWindow(blackhole: Blackhole) {
        blackhole.consume(
            renewalElector.runIfLeader(renewalLockName) {
                TimeUnit.MILLISECONDS.sleep(RENEWAL_ACTION_DWELL_MILLIS)
                1
            },
        )
    }

    private fun createRedissonClient(): RedissonClient {
        val redis = RedisServer.Launcher.redis
        return Redisson.create(
            Config().apply {
                useSingleServer()
                    .setAddress(redis.url)
                    .setConnectionPoolSize(8)
                    .setConnectionMinimumIdleSize(2)
            },
        )
    }

    private fun newLockName(kind: String): String =
        "redis-lease-bench-$kind-${Base58.randomString(10).lowercase()}"

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure { log.warn("Redis lease benchmark resource cleanup failed. resource=$resource, mode=$mode", it) }
    }

    companion object : KLogging()
}

@State(Scope.Benchmark)
open class SuspendRedisLeaseExtensionBenchmark {

    @Param(
        "lettuce-normal",
        "lettuce-auto-extend",
        "redisson-normal",
        "redisson-auto-extend",
    )
    lateinit var mode: String

    private lateinit var redisMode: RedisLeaseExtensionMode
    private lateinit var quickElector: SuspendLeaderElector
    private lateinit var renewalElector: SuspendLeaderElector
    private lateinit var quickLockName: String
    private lateinit var renewalLockName: String
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null

    @Setup
    fun setup() = runBlocking {
        redisMode = RedisLeaseExtensionMode.from(mode)
        quickLockName = newLockName("suspend-quick")
        renewalLockName = newLockName("suspend-renewal")

        when (redisMode.backend) {
            RedisLeaseBackend.LETTUCE -> {
                val redis = RedisServer.Launcher.redis
                val client = RedisClient.create(redis.url)
                val connection = client.connect(StringCodec.UTF8)
                lettuceClient = client
                lettuceConnection = connection
                quickElector = LettuceSuspendLeaderElector(connection, redisMode.quickOptions())
                renewalElector = LettuceSuspendLeaderElector(connection, redisMode.renewalOptions())
            }
            RedisLeaseBackend.REDISSON -> {
                val client = createRedissonClient()
                redissonClient = client
                quickElector = RedissonSuspendLeaderElector(client, redisMode.quickOptions())
                renewalElector = RedissonSuspendLeaderElector(client, redisMode.renewalOptions())
            }
        }

        require(quickElector.runIfLeader("$quickLockName-smoke") { true } == true) {
            "Redis lease benchmark failed suspend quick smoke check. mode=$mode"
        }
        require(renewalElector.runIfLeader("$renewalLockName-smoke") { true } == true) {
            "Redis lease benchmark failed suspend renewal smoke check. mode=$mode"
        }
    }

    @TearDown
    fun tearDown() {
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
    }

    @Benchmark
    fun runIfLeader(blackhole: Blackhole) = runBlocking {
        blackhole.consume(quickElector.runIfLeader(quickLockName) { 1 })
    }

    @Benchmark
    fun runIfLeaderWithRenewalWindow(blackhole: Blackhole) = runBlocking {
        blackhole.consume(
            renewalElector.runIfLeader(renewalLockName) {
                delay(RENEWAL_ACTION_DWELL_MILLIS)
                1
            },
        )
    }

    private fun createRedissonClient(): RedissonClient {
        val redis = RedisServer.Launcher.redis
        return Redisson.create(
            Config().apply {
                useSingleServer()
                    .setAddress(redis.url)
                    .setConnectionPoolSize(8)
                    .setConnectionMinimumIdleSize(2)
            },
        )
    }

    private fun newLockName(kind: String): String =
        "redis-lease-bench-$kind-${Base58.randomString(10).lowercase()}"

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure { log.warn("Redis lease benchmark resource cleanup failed. resource=$resource, mode=$mode", it) }
    }

    companion object : KLogging()
}

private enum class RedisLeaseBackend {
    LETTUCE,
    REDISSON,
}

private enum class RedisLeaseExtensionMode(
    val paramName: String,
    val backend: RedisLeaseBackend,
    val autoExtend: Boolean,
) {
    LETTUCE_NORMAL("lettuce-normal", RedisLeaseBackend.LETTUCE, autoExtend = false),
    LETTUCE_AUTO_EXTEND("lettuce-auto-extend", RedisLeaseBackend.LETTUCE, autoExtend = true),
    REDISSON_NORMAL("redisson-normal", RedisLeaseBackend.REDISSON, autoExtend = false),
    REDISSON_AUTO_EXTEND("redisson-auto-extend", RedisLeaseBackend.REDISSON, autoExtend = true),
    ;

    fun quickOptions(): LeaderElectionOptions =
        LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 60.seconds, autoExtend = autoExtend)

    fun renewalOptions(): LeaderElectionOptions =
        LeaderElectionOptions(waitTime = 1.seconds, leaseTime = RENEWAL_LEASE_TIME, autoExtend = autoExtend)

    companion object {
        fun from(paramName: String): RedisLeaseExtensionMode =
            entries.firstOrNull { it.paramName == paramName }
                ?: error("Unsupported Redis lease extension benchmark mode: $paramName")
    }
}

private val RENEWAL_LEASE_TIME = 90.milliseconds
private const val RENEWAL_ACTION_DWELL_MILLIS = 45L

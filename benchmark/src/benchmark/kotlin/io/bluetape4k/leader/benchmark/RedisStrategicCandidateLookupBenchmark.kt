package io.bluetape4k.leader.benchmark

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.StrategicLeaderElector
import io.bluetape4k.leader.lettuce.LettuceStrategicLeaderElector
import io.bluetape4k.leader.redisson.RedissonStrategicLeaderElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.strategies.ScoredElectionStrategy
import io.bluetape4k.leader.strategy.scorers.IdleTimeScorer
import io.bluetape4k.leader.strategy.scorers.SuccessRateScorer
import io.bluetape4k.leader.strategy.scorers.WeightedScorer
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.bluetape4k.testcontainers.storage.RedisServer
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.StringCodec
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
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
class RedisStrategicCandidateLookupBenchmark {

    @Param("lettuce", "redisson")
    lateinit var backend: String

    @Param("16", "128", "1024")
    var candidateCount: Int = 0

    private lateinit var elector: StrategicLeaderElector
    private lateinit var lockName: String
    private val strategy = ScoredElectionStrategy(WeightedScorer(IdleTimeScorer to 0.2, SuccessRateScorer to 0.8))
    private val options = LeaderElectionOptions(leaseTime = 30.seconds)
    private var lettuceClient: RedisClient? = null
    private var lettuceConnection: StatefulRedisConnection<String, String>? = null
    private var redissonClient: RedissonClient? = null

    @Setup
    fun setup() {
        lockName = "redis-strategic-bench-$backend-${Base58.randomString(10).lowercase()}"
        elector = when (backend) {
            "lettuce" -> createLettuceElector()
            "redisson" -> createRedissonElector()
            else -> error("Unsupported backend: $backend")
        }
        repeat(candidateCount) { index ->
            elector.registerCandidate(
                lockName,
                CandidateInfo(
                    nodeId = "node-$index",
                    registeredAt = Instant.EPOCH.plusMillis(index.toLong()),
                    successCount = index.toLong(),
                    failureCount = (candidateCount - index).toLong(),
                ),
                ttl = 30.seconds,
            )
        }
        require(elector.listCandidates(lockName).size == candidateCount) {
            "Redis strategic benchmark candidate registration failed. backend=$backend, candidateCount=$candidateCount"
        }
    }

    @TearDown
    fun tearDown() {
        closeResource("lettuceConnection") { lettuceConnection?.close() }
        closeResource("lettuceClient") { lettuceClient?.shutdown() }
        closeResource("redissonClient") { redissonClient?.shutdown() }
    }

    @Benchmark
    fun listCandidates(blackhole: Blackhole) {
        blackhole.consume(elector.listCandidates(lockName))
    }

    @Benchmark
    fun runScoredElection(blackhole: Blackhole) {
        blackhole.consume(elector.runIfLeader(lockName, strategy, options) { 1 })
    }

    private fun createLettuceElector(): StrategicLeaderElector {
        val redis = RedisServer.Launcher.redis
        val client = RedisClient.create(redis.url)
        val connection = client.connect(StringCodec.UTF8)
        lettuceClient = client
        lettuceConnection = connection
        return LettuceStrategicLeaderElector(connection, "node-0")
    }

    private fun createRedissonElector(): StrategicLeaderElector {
        val redis = RedisServer.Launcher.redis
        val client = Redisson.create(
            Config().apply {
                useSingleServer()
                    .setAddress(redis.url)
                    .setConnectionPoolSize(8)
                    .setConnectionMinimumIdleSize(2)
            },
        )
        redissonClient = client
        return RedissonStrategicLeaderElector(client, "node-0")
    }

    private inline fun closeResource(resource: String, block: () -> Unit) {
        runCatching(block)
            .onFailure {
                log.warn(it) { "Redis strategic benchmark resource cleanup failed. resource=$resource, backend=$backend" }
            }
    }

    companion object: KLogging()
}

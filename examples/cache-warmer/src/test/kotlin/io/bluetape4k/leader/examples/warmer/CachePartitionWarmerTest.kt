package io.bluetape4k.leader.examples.warmer

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldContainSame
import io.bluetape4k.concurrent.awaitTermination
import io.bluetape4k.concurrent.get
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CachePartitionWarmerTest: AbstractCachePartitionWarmerTest() {

    companion object: KLogging() {
        private val DEFAULT_PARTITIONS = listOf("region-asia", "region-eu", "region-us")
    }

    private fun electorFactory(): (String, LeaderElectionOptions) -> LeaderElector = { _, options ->
        HazelcastLeaderElector(hazelcastClient, options)
    }

    @Test
    fun `단일 인스턴스 - 모든 파티션이 warmed 에 포함된다`() {
        val warmedPartitions = ConcurrentLinkedQueue<String>()

        val warmer = CachePartitionWarmer(
            electorFactory = electorFactory(),
            options = CachePartitionWarmerOptions(
                nodeId = "single-node",
                lockNamePrefix = randomPrefix(),
                partitions = DEFAULT_PARTITIONS,
                waitTime = 500.milliseconds,
                leaseTime = 5.seconds,
            ),
            warmFunction = { partitionId -> warmedPartitions.add(partitionId) },
        )

        val result = warmer.warmAll()

        log.debug { "warm result=$result" }
        result.warmed shouldContainSame DEFAULT_PARTITIONS
        result.skipped.shouldBeEmpty()
        result.failed.shouldBeEmpty()

        warmedPartitions shouldContainSame DEFAULT_PARTITIONS
    }

    @Test
    fun `3 인스턴스 동시 - 동일 파티션 워밍은 겹치지 않는다`() {
        val lockPrefix = randomPrefix()
        val instanceCount = 3
        val warmCounts = ConcurrentHashMap<String, AtomicInteger>()
        val activeCounts = ConcurrentHashMap<String, AtomicInteger>()
        val maxConcurrent = ConcurrentHashMap<String, AtomicInteger>()
        DEFAULT_PARTITIONS.forEach { warmCounts[it] = AtomicInteger(0) }
        DEFAULT_PARTITIONS.forEach { activeCounts[it] = AtomicInteger(0) }
        DEFAULT_PARTITIONS.forEach { maxConcurrent[it] = AtomicInteger(0) }

        val executor = Executors.newFixedThreadPool(instanceCount)
        val results = ConcurrentLinkedQueue<WarmResult>()

        try {
            val futures = List(instanceCount) { idx ->
                executor.submit {
                    val warmer = CachePartitionWarmer(
                        electorFactory = electorFactory(),
                        options = CachePartitionWarmerOptions(
                            nodeId = "node-$idx",
                            lockNamePrefix = lockPrefix,
                            partitions = DEFAULT_PARTITIONS,
                            waitTime = 100.milliseconds,
                            leaseTime = 5.seconds,
                        ),
                        warmFunction = { partitionId ->
                            log.debug { "partitionId[$partitionId] 의 cache 를 warm up 을 시작합니다..." }
                            val active = activeCounts.getValue(partitionId).incrementAndGet()
                            maxConcurrent.getValue(partitionId).accumulateAndGet(active, ::maxOf)
                            try {
                                warmCounts.getValue(partitionId).incrementAndGet()
                                // follower가 같은 partition lock을 기다리는 동안 leader를 유지한다.
                                Thread.sleep(150)
                            } finally {
                                activeCounts.getValue(partitionId).decrementAndGet()
                            }
                            log.debug {
                                "partitionId[$partitionId] 의 cache 를 warm up 을 완료했습니다. " +
                                        "warm count=${warmCounts.getValue(partitionId).get()}"
                            }
                        },
                    )
                    results.add(warmer.warmAll())
                }
            }
            futures.forEach { it.get(30.seconds) }
        } finally {
            executor.shutdown()
            executor.awaitTermination(5.seconds)
        }

        // waitTime 동안 follower가 순차 실행될 수 있지만, 같은 partition의 워밍은 겹치지 않는다.
        DEFAULT_PARTITIONS.forEach { partitionId ->
            warmCounts.getValue(partitionId).get() shouldBeGreaterThan 0
            maxConcurrent.getValue(partitionId).get() shouldBeEqualTo 1
        }

        val totalWarmed = results.sumOf { it.warmed.size }
        val totalSkipped = results.sumOf { it.skipped.size }
        val totalFailed = results.sumOf { it.failed.size }

        totalWarmed shouldBeEqualTo warmCounts.values.sumOf { it.get() }
        totalWarmed + totalSkipped shouldBeEqualTo instanceCount * DEFAULT_PARTITIONS.size
        totalFailed shouldBeEqualTo 0
    }

    @Test
    fun `warmFunction 일부 파티션 예외 - failed 기록 후 나머지 파티션 계속 처리`() {
        val failingPartition = "region-eu"
        val errorMessage = "워밍 실패 시뮬레이션"
        val warmedPartitions = ConcurrentLinkedQueue<String>()

        val warmer = CachePartitionWarmer(
            electorFactory = electorFactory(),
            options = CachePartitionWarmerOptions(
                nodeId = "fail-node",
                lockNamePrefix = randomPrefix(),
                partitions = DEFAULT_PARTITIONS,
                waitTime = 500.milliseconds,
                leaseTime = 5.seconds,
            ),
            warmFunction = { partitionId ->
                if (partitionId == failingPartition) {
                    error(errorMessage)
                }
                warmedPartitions.add(partitionId)
            },
        )

        val result = warmer.warmAll()

        log.debug { "warm result=$result" }

        // failingPartition 은 failed 에, 나머지는 warmed 에
        result.warmed shouldContainSame DEFAULT_PARTITIONS.filterNot { it == failingPartition }
        result.skipped.shouldBeEmpty()
        result.failed.keys shouldContainSame listOf(failingPartition)
        result.failed[failingPartition] shouldBeEqualTo errorMessage

        warmedPartitions shouldContainSame DEFAULT_PARTITIONS.filterNot { it == failingPartition }
    }

    @Test
    fun `nodeId blank - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            CachePartitionWarmerOptions(
                nodeId = "  ",
                lockNamePrefix = "warmer",
                partitions = DEFAULT_PARTITIONS,
            )
        }
    }

    @Test
    fun `lockNamePrefix blank - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            CachePartitionWarmerOptions(
                nodeId = "node",
                lockNamePrefix = "",
                partitions = DEFAULT_PARTITIONS,
            )
        }
    }

    @Test
    fun `partitions 빈 목록 - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            CachePartitionWarmerOptions(
                nodeId = "node",
                lockNamePrefix = "warmer",
                partitions = emptyList(),
            )
        }
    }

    @Test
    fun `partitions blank 항목 - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            CachePartitionWarmerOptions(
                nodeId = "node",
                lockNamePrefix = "warmer",
                partitions = listOf("region-asia", "  ", "region-us"),
            )
        }
    }

    @Test
    fun `result nodeId - options nodeId 와 동일`() {
        val nodeId = "verify-node-id"

        val warmer = CachePartitionWarmer(
            electorFactory = electorFactory(),
            options = CachePartitionWarmerOptions(
                nodeId = nodeId,
                lockNamePrefix = randomPrefix(),
                partitions = listOf("only-one"),
                waitTime = 200.milliseconds,
                leaseTime = 100.milliseconds,
            ),
            warmFunction = {
                log.debug { "warm up cache ... partition=$it" }
            },
        )
        val result = warmer.warmAll()

        log.debug { "result=$result" }
        result.nodeId shouldBeEqualTo nodeId
        result.warmed shouldContain "only-one"
        result.skipped.shouldBeEmpty()
        result.failed.shouldBeEmpty()
    }
}

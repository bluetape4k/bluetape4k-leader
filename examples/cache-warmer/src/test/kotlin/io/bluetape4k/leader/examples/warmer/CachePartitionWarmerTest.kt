package io.bluetape4k.leader.examples.warmer

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldContainSame
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CachePartitionWarmerTest: AbstractCachePartitionWarmerTest() {

    companion object: KLogging() {
        private val DEFAULT_PARTITIONS = listOf("region-asia", "region-eu", "region-us")
    }

    private fun electorFactory(): (String, LeaderElectionOptions) -> LeaderElector =
        { _, options -> HazelcastLeaderElector(hazelcastClient, options) }

    @Test
    fun `단일 인스턴스 - 모든 파티션이 warmed 에 포함된다`() {
        val warmedPartitions = CopyOnWriteArrayList<String>()
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

        result.warmed shouldContainSame DEFAULT_PARTITIONS
        result.skipped.shouldBeEmpty()
        result.failed.shouldBeEmpty()
        warmedPartitions shouldContainSame DEFAULT_PARTITIONS
    }

    @Test
    fun `3 인스턴스 동시 - 각 파티션 정확히 1번만 warmed`() {
        val lockPrefix = randomPrefix()
        val instanceCount = 3
        val warmCounts = ConcurrentHashMap<String, AtomicInteger>()
        DEFAULT_PARTITIONS.forEach { warmCounts[it] = AtomicInteger(0) }

        val executor = Executors.newFixedThreadPool(instanceCount)
        val results = CopyOnWriteArrayList<WarmResult>()

        try {
            val futures = (1..instanceCount).map { idx ->
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
                            warmCounts.getValue(partitionId).incrementAndGet()
                            // 다른 인스턴스가 동일 lock 획득 시도 중에 끝나지 않도록 보장
                            Thread.sleep(150)
                        },
                    )
                    results.add(warmer.warmAll())
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        // 각 partition 이 정확히 1번만 워밍됨
        DEFAULT_PARTITIONS.forEach { partitionId ->
            warmCounts.getValue(partitionId).get() shouldBeEqualTo 1
        }

        // 전체 warmed 합 = partition 수, skipped 합 = (instanceCount - 1) * partition 수
        val totalWarmed = results.sumOf { it.warmed.size }
        val totalSkipped = results.sumOf { it.skipped.size }
        val totalFailed = results.sumOf { it.failed.size }

        totalWarmed shouldBeEqualTo DEFAULT_PARTITIONS.size
        totalSkipped shouldBeEqualTo (instanceCount - 1) * DEFAULT_PARTITIONS.size
        totalFailed shouldBeEqualTo 0
    }

    @Test
    fun `warmFunction 일부 파티션 예외 - failed 기록 후 나머지 파티션 계속 처리`() {
        val failingPartition = "region-eu"
        val errorMessage = "워밍 실패 시뮬레이션"
        val warmedPartitions = CopyOnWriteArrayList<String>()

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
                    throw IllegalStateException(errorMessage)
                }
                warmedPartitions.add(partitionId)
            },
        )

        val result = warmer.warmAll()

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
                leaseTime = 3.seconds,
            ),
            warmFunction = { /* no-op */ },
        )
        val result = warmer.warmAll()
        result.nodeId shouldBeEqualTo nodeId
        result.warmed shouldContain "only-one"
    }
}

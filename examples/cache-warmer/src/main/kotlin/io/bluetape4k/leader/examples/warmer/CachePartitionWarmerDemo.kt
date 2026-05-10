package io.bluetape4k.leader.examples.warmer

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.core.HazelcastInstance
import io.bluetape4k.leader.hazelcast.HazelcastLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.testcontainers.storage.HazelcastServer
import io.bluetape4k.utils.ShutdownQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3-인스턴스 × 3-파티션 캐시 워밍 데모.
 *
 * Testcontainers Hazelcast 를 자동 기동하고, 동일 lockNamePrefix + partitions 를 공유하는 3개의
 * [CachePartitionWarmer] 를 별도 스레드에서 동시 호출한다. 각 partition 에 대해 정확히 1 인스턴스만
 * [warmFunction] 을 실행하는지 ConcurrentHashMap 카운트로 검증한다.
 */
object CachePartitionWarmerDemo: KLogging() {

    private const val DEMO_LOCK_PREFIX = "warmer:product-cache"
    private val DEMO_PARTITIONS = listOf("region-asia", "region-eu", "region-us")
    private const val DEMO_INSTANCE_COUNT = 3

    @JvmStatic
    fun main(args: Array<String>) {
        val server = HazelcastServer.Launcher.hazelcast
        val config = ClientConfig().apply {
            networkConfig.addAddress(server.url)
        }
        val client: HazelcastInstance = HazelcastClient.newHazelcastClient(config).also {
            ShutdownQueue.register { runCatching { it.shutdown() } }
        }

        val warmedBy = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
        DEMO_PARTITIONS.forEach { warmedBy[it] = CopyOnWriteArrayList() }
        val totalWarmed = AtomicInteger(0)

        val executor = Executors.newFixedThreadPool(DEMO_INSTANCE_COUNT)

        try {
            log.info { "=== 캐시 워밍 데모 시작 ===" }
            log.info { "$DEMO_INSTANCE_COUNT 인스턴스 × ${DEMO_PARTITIONS.size} 파티션 동시 워밍 시도" }

            val futures = (1..DEMO_INSTANCE_COUNT).map { idx ->
                executor.submit {
                    val nodeId = "node-$idx"
                    val warmer = CachePartitionWarmer(
                        electorFactory = { _, options -> HazelcastLeaderElector(client, options) },
                        options = CachePartitionWarmerOptions(
                            nodeId = nodeId,
                            lockNamePrefix = DEMO_LOCK_PREFIX,
                            partitions = DEMO_PARTITIONS,
                        ),
                        warmFunction = { partitionId ->
                            warmedBy.getValue(partitionId).add(nodeId)
                            totalWarmed.incrementAndGet()
                            log.info { "[$nodeId] partition=$partitionId 워밍 처리 (시뮬레이션)" }
                            Thread.sleep(200)
                        },
                    )
                    val result = warmer.warmAll()
                    log.info { "[$nodeId] result=$result" }
                }
            }
            futures.forEach { it.get() }

            log.info { "=== 결과 ===" }
            log.info { "전체 워밍 횟수=${totalWarmed.get()} (기대값=${DEMO_PARTITIONS.size})" }
            DEMO_PARTITIONS.forEach { partitionId ->
                val winners = warmedBy.getValue(partitionId)
                log.info { "partition=$partitionId 워밍 인스턴스=$winners" }
            }
        } finally {
            executor.shutdown()
        }
    }
}

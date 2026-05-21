package io.bluetape4k.leader.examples.ratelimit

import io.bluetape4k.bucket4j.distributed.AsyncBucketProxyProvider
import io.bluetape4k.bucket4j.distributed.redis.lettuceBasedProxyManagerOf
import io.bluetape4k.bucket4j.ratelimit.distributed.DistributedSuspendRateLimiter
import io.bluetape4k.codec.Base58
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.github.bucket4j.BucketConfiguration
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.StringCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Serializable
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Three-node leader-election plus distributed rate-limiter demo.
 *
 * ## Contract
 *
 * One leader node schedules the work. Multiple worker nodes then consume the
 * scheduled work through one Redis-backed Bucket4j quota so the aggregate
 * external API calls do not exceed [quotaPerSecond] per second.
 */
object RateLimiterDemo: KLogging() {

    private const val DEFAULT_NODE_COUNT = 3
    private const val DEFAULT_QUOTA_PER_SECOND = 10
    private const val DEFAULT_WINDOW_SECONDS = 3
    private const val DEFAULT_ATTEMPTS_PER_SECOND = 15

    @JvmStatic
    fun main(args: Array<String>) {
        val redis = RedisServer.Launcher.redis
        val report = runScenario(redis.url)

        log.info { "=== rate limiter demo result ===" }
        report.dispatchReports.forEach { dispatch ->
            log.info {
                "[${dispatch.nodeId}] ${dispatch.status} scheduledItems=${dispatch.scheduledItems.size}"
            }
        }
        report.workerReports
            .groupBy { it.nodeId }
            .forEach { (nodeId, reports) ->
                val consumed = reports.count { it.status == RateLimiterDemoStatus.CONSUMED }
                val rejected = reports.count { it.status == RateLimiterDemoStatus.REJECTED }
                log.info { "[$nodeId] CONSUMED=$consumed REJECTED=$rejected" }
            }
        log.info { "total external API calls=${report.totalCalls}, expectedMax=${report.expectedMaxCalls}" }
    }

    fun runScenario(
        redisUrl: String,
        nodeCount: Int = DEFAULT_NODE_COUNT,
        quotaPerSecond: Int = DEFAULT_QUOTA_PER_SECOND,
        windowSeconds: Int = DEFAULT_WINDOW_SECONDS,
        attemptsPerSecond: Int = DEFAULT_ATTEMPTS_PER_SECOND,
    ): RateLimiterDemoReport {
        val client = RedisClient.create(redisUrl).also {
            ShutdownQueue.register { runCatching { it.shutdown() } }
        }
        val dispatchExecutor = Executors.newFixedThreadPool(nodeCount)
        val lockName = "rate-limiter:${Base58.randomString(8)}"
        val quotaKey = "external-api:${Base58.randomString(8)}"
        val externalApi = ExternalApiProbe()

        return try {
            val dispatchReports = dispatchOnce(
                client = client,
                dispatchExecutor = dispatchExecutor,
                nodeCount = nodeCount,
                lockName = lockName,
                totalWorkItems = windowSeconds * attemptsPerSecond,
            )
            val scheduledItems = dispatchReports.singleOrNull {
                it.status == RateLimiterDemoStatus.SCHEDULED
            }?.scheduledItems.orEmpty()

            val rateLimiter = newRateLimiter(
                client = client,
                quotaPerSecond = quotaPerSecond,
                quotaKeyPrefix = "rate-limiter:${Base58.randomString(8)}:",
            )
            val workerReports = consumeScheduledWork(
                nodeCount = nodeCount,
                scheduledItems = scheduledItems,
                attemptsPerSecond = attemptsPerSecond,
                windowSeconds = windowSeconds,
                rateLimiter = rateLimiter,
                externalApi = externalApi,
                quotaKey = quotaKey,
            )

            RateLimiterDemoReport(
                dispatchReports = dispatchReports,
                workerReports = workerReports,
                totalCalls = externalApi.totalCalls,
                quotaPerSecond = quotaPerSecond,
                windowSeconds = windowSeconds,
            )
        } finally {
            dispatchExecutor.shutdown()
            client.shutdown()
        }
    }

    private fun dispatchOnce(
        client: RedisClient,
        dispatchExecutor: java.util.concurrent.ExecutorService,
        nodeCount: Int,
        lockName: String,
        totalWorkItems: Int,
    ): List<DispatchReport> {
        val losersFinished = CountDownLatch(nodeCount - 1)
        val connections = (1..nodeCount).map {
            client.connect(StringCodec.UTF8)
        }

        return try {
            val futures = connections.mapIndexed { index, connection ->
                dispatchExecutor.submit<DispatchReport> {
                    val nodeId = "node-${index + 1}"
                    val scheduler = LeaderDispatchScheduler(
                        nodeId = nodeId,
                        connection = connection,
                        lockName = lockName,
                        waitTime = 500.milliseconds,
                        leaseTime = 10.seconds,
                    )
                    val report = scheduler.schedule {
                        if (nodeCount > 1) {
                            losersFinished.await(5, TimeUnit.SECONDS)
                        }
                        (1..totalWorkItems).map { itemIndex -> "item-$itemIndex" }
                    }
                    if (report.status != RateLimiterDemoStatus.SCHEDULED) {
                        losersFinished.countDown()
                    }
                    report
                }
            }
            futures.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            connections.forEach { it.closeSafe() }
        }
    }

    private fun newRateLimiter(
        client: RedisClient,
        quotaPerSecond: Int,
        quotaKeyPrefix: String,
    ): DistributedSuspendRateLimiter {
        val configuration = BucketConfiguration.builder()
            .addLimit { limit ->
                limit
                    .capacity(quotaPerSecond.toLong())
                    .refillGreedy(quotaPerSecond.toLong(), Duration.ofSeconds(1))
            }
            .build()

        val proxyManager = lettuceBasedProxyManagerOf(client) {}
        return DistributedSuspendRateLimiter(
            AsyncBucketProxyProvider(
                asyncProxyManager = proxyManager.asAsync(),
                bucketConfiguration = configuration,
                keyPrefix = quotaKeyPrefix,
            ),
        )
    }

    private fun consumeScheduledWork(
        nodeCount: Int,
        scheduledItems: List<String>,
        attemptsPerSecond: Int,
        windowSeconds: Int,
        rateLimiter: DistributedSuspendRateLimiter,
        externalApi: ExternalApiProbe,
        quotaKey: String,
    ): List<WorkerCallReport> = runBlocking {
        val workers = (1..nodeCount).map {
            RateLimitedApiWorker(
                nodeId = "node-$it",
                rateLimiter = rateLimiter,
                externalApi = externalApi,
                quotaKey = quotaKey,
            )
        }

        val reports = mutableListOf<WorkerCallReport>()
        repeat(windowSeconds) { second ->
            val batch = scheduledItems
                .drop(second * attemptsPerSecond)
                .take(attemptsPerSecond)
            val batchReports = batch.mapIndexed { index, itemId ->
                async {
                    val worker = workers[index % workers.size]
                    worker.call(itemId)
                }
            }.awaitAll()
            reports += batchReports

            if (second < windowSeconds - 1) {
                withContext(Dispatchers.Default) {
                    delay(1_050)
                }
            }
        }
        reports
    }
}

enum class RateLimiterDemoStatus {
    SCHEDULED,
    CONSUMED,
    REJECTED,
    ERROR,
}

data class RateLimiterDemoReport(
    val dispatchReports: List<DispatchReport>,
    val workerReports: List<WorkerCallReport>,
    val totalCalls: Int,
    val quotaPerSecond: Int,
    val windowSeconds: Int,
): Serializable {
    val scheduledNodeCount: Int
        get() = dispatchReports.count { it.status == RateLimiterDemoStatus.SCHEDULED }

    val expectedMaxCalls: Int
        get() = quotaPerSecond * windowSeconds

    val consumedCalls: Int
        get() = workerReports.count { it.status == RateLimiterDemoStatus.CONSUMED }

    companion object {
        private const val serialVersionUID: Long = -1588426239108912955L
    }
}

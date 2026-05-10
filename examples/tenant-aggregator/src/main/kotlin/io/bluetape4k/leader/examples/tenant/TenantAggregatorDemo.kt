package io.bluetape4k.leader.examples.tenant

import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

/**
 * 3 인스턴스 × 3 테넌트 멀티테넌트 집계 데모.
 *
 * H2 in-memory R2DBC 데이터베이스를 공유하며 3개의 [TenantAggregator] 인스턴스가 동일한
 * `lockNamePrefix` + `tenants` 를 polling 한다. 각 테넌트마다 정확히 1 인스턴스만 집계 실행되는지
 * `ConcurrentHashMap` 카운트로 검증한다.
 */
object TenantAggregatorDemo: KLogging() {

    private const val DEMO_LOCK_PREFIX = "tenant-aggregator:demo"
    private val DEMO_TENANTS = listOf("tenant-A", "tenant-B", "tenant-C")
    private const val DEMO_INSTANCE_COUNT = 3
    private const val DEMO_DURATION_SECONDS = 6L

    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        // 모든 인스턴스가 공유하는 H2 in-memory database (동일 URL 로 같은 DB 참조)
        val r2dbcUrl = "r2dbc:h2:mem:///tenant_aggregator_demo;MODE=MySQL;DB_CLOSE_DELAY=-1"
        val db = R2dbcDatabase.connect(r2dbcUrl, user = "", password = "")

        val aggregateCounts = ConcurrentHashMap<String, AtomicInteger>()
        DEMO_TENANTS.forEach { aggregateCounts[it] = AtomicInteger(0) }

        // tenant 별 동시 실행 인스턴스 수를 추적 (1을 초과하면 격리 실패)
        val concurrentRunners = ConcurrentHashMap<String, AtomicInteger>()
        DEMO_TENANTS.forEach { concurrentRunners[it] = AtomicInteger(0) }
        val violations = AtomicInteger(0)

        log.info { "=== 멀티테넌트 집계 데모 시작 ===" }
        log.info { "$DEMO_INSTANCE_COUNT 인스턴스 × ${DEMO_TENANTS.size} 테넌트, ${DEMO_DURATION_SECONDS}s 동안 polling" }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aggregators = (1..DEMO_INSTANCE_COUNT).map { idx ->
            val nodeId = "node-$idx"
            TenantAggregator(
                electorFactory = { _, options ->
                    ExposedR2DbcSuspendLeaderElector(
                        db,
                        ExposedR2dbcLeaderElectionOptions(leaderOptions = options),
                    )
                },
                options = TenantAggregatorOptions(
                    nodeId = nodeId,
                    lockNamePrefix = DEMO_LOCK_PREFIX,
                    tenants = DEMO_TENANTS,
                    pollInterval = 300.milliseconds,
                    waitTime = 200.milliseconds,
                    leaseTime = 5.seconds,
                ),
                aggregateFunction = { tenantId ->
                    val now = concurrentRunners.getValue(tenantId).incrementAndGet()
                    if (now > 1) {
                        violations.incrementAndGet()
                        log.warn { "[$nodeId] tenant=$tenantId 동시 실행 위반 (concurrent=$now)" }
                    }
                    try {
                        aggregateCounts.getValue(tenantId).incrementAndGet()
                        log.info { "[$nodeId] tenant=$tenantId 집계 실행" }
                        delay(150.milliseconds)
                    } finally {
                        concurrentRunners.getValue(tenantId).decrementAndGet()
                    }
                },
            )
        }

        try {
            aggregators.forEach { it.start(scope) }
            delay(DEMO_DURATION_SECONDS.seconds)
        } finally {
            aggregators.forEach { it.stopGracefully(2.seconds) }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }

        log.info { "=== 결과 ===" }
        DEMO_TENANTS.forEach { tenantId ->
            log.info { "tenant=$tenantId 집계 호출 횟수=${aggregateCounts.getValue(tenantId).get()}" }
        }
        log.info { "동시 실행 위반 횟수=${violations.get()} (기대값=0)" }
    }
}

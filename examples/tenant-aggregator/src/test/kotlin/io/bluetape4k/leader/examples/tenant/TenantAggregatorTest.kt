package io.bluetape4k.leader.examples.tenant

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterOrEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElector
import io.bluetape4k.leader.exposed.r2dbc.ExposedR2dbcLeaderElectionOptions
import io.bluetape4k.logging.KLogging
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * [TenantAggregator] 통합 테스트 — Exposed R2DBC + H2/PostgreSQL Testcontainers 기반.
 */
class TenantAggregatorTest: AbstractTenantAggregatorTest() {

    companion object: KLogging() {
        private val DEFAULT_TENANTS = listOf("tenant-A", "tenant-B", "tenant-C")
        private val INSTANCE_TIMEOUT = 30.seconds
    }

    private fun fastOptions(
        nodeId: String,
        lockNamePrefix: String,
        tenants: List<String> = DEFAULT_TENANTS,
        pollInterval: Duration = 100.milliseconds,
        waitTime: Duration = 200.milliseconds,
        leaseTime: Duration = 5.seconds,
    ) = TenantAggregatorOptions(
        nodeId = nodeId,
        lockNamePrefix = lockNamePrefix,
        tenants = tenants,
        pollInterval = pollInterval,
        waitTime = waitTime,
        leaseTime = leaseTime,
    )

    private fun electorFactory(
        db: R2dbcDatabase,
    ): suspend (String, LeaderElectionOptions) -> SuspendLeaderElector = { _, options ->
        ExposedR2DbcSuspendLeaderElector(
            db,
            ExposedR2dbcLeaderElectionOptions(leaderOptions = options),
        )
    }

    private suspend fun waitUntil(
        timeout: Duration = 10.seconds,
        condition: suspend () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(50.milliseconds)
        }
        return false
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `단일 인스턴스 - 모든 테넌트가 polling 시작`(testDB: TestTenantDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockPrefix = randomPrefix()

        val seen = ConcurrentHashMap.newKeySet<String>()
        val aggregator = TenantAggregator(
            electorFactory = electorFactory(db),
            options = fastOptions("solo", lockPrefix),
            aggregateFunction = { tenantId -> seen.add(tenantId) },
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            aggregator.start(scope)
            val ok = waitUntil(INSTANCE_TIMEOUT) { seen.size == DEFAULT_TENANTS.size }
            ok.shouldBeTrue()
            DEFAULT_TENANTS.forEach { seen.contains(it).shouldBeTrue() }
        } finally {
            aggregator.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `3 인스턴스 동시 - 각 테넌트는 정확히 1 인스턴스만 polling`(testDB: TestTenantDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockPrefix = randomPrefix()

        // 각 tenant 의 동시 실행 인스턴스 수 추적 (1을 초과하면 위반)
        val concurrentRunners = ConcurrentHashMap<String, AtomicInteger>()
        DEFAULT_TENANTS.forEach { concurrentRunners[it] = AtomicInteger(0) }
        val aggregateCounts = ConcurrentHashMap<String, AtomicInteger>()
        DEFAULT_TENANTS.forEach { aggregateCounts[it] = AtomicInteger(0) }
        val violations = AtomicInteger(0)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aggregators = (1..3).map { idx ->
            TenantAggregator(
                electorFactory = electorFactory(db),
                options = fastOptions("node-$idx", lockPrefix, leaseTime = 3.seconds),
                aggregateFunction = { tenantId ->
                    val now = concurrentRunners.getValue(tenantId).incrementAndGet()
                    if (now > 1) violations.incrementAndGet()
                    try {
                        aggregateCounts.getValue(tenantId).incrementAndGet()
                        delay(150.milliseconds)
                    } finally {
                        concurrentRunners.getValue(tenantId).decrementAndGet()
                    }
                },
            )
        }

        try {
            aggregators.forEach { it.start(scope) }
            // 모든 테넌트가 최소 1번 이상 집계되도록 기다림
            val ok = waitUntil(INSTANCE_TIMEOUT) {
                DEFAULT_TENANTS.all { aggregateCounts.getValue(it).get() >= 1 }
            }
            ok.shouldBeTrue()
            // 추가 사이클 — 위반이 있다면 발생할 시간 확보
            delay(2.seconds)

            // 동시 실행 위반 0
            violations.get() shouldBeEqualTo 0
            DEFAULT_TENANTS.forEach { aggregateCounts.getValue(it).get() shouldBeGreaterOrEqualTo 1 }
        } finally {
            aggregators.forEach { it.stopGracefully(2.seconds) }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `aggregate 함수 예외 - 다음 사이클은 계속 실행`(testDB: TestTenantDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockPrefix = randomPrefix()
        val tenantId = "tenant-X"

        val callCount = AtomicInteger(0)
        val firstFailed = CompletableDeferred<Unit>()
        val secondSucceeded = CompletableDeferred<Unit>()

        val aggregator = TenantAggregator(
            electorFactory = electorFactory(db),
            options = fastOptions("retry-node", lockPrefix, tenants = listOf(tenantId)),
            aggregateFunction = { _ ->
                val n = callCount.incrementAndGet()
                if (n == 1) {
                    firstFailed.complete(Unit)
                    throw IllegalStateException("first cycle fails")
                }
                if (n >= 2 && !secondSucceeded.isCompleted) secondSucceeded.complete(Unit)
            },
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            aggregator.start(scope)
            withTimeoutOrNull(INSTANCE_TIMEOUT) { firstFailed.await() }?.let { } ?: error("first cycle did not run")
            withTimeoutOrNull(INSTANCE_TIMEOUT) { secondSucceeded.await() }?.let { } ?: error("second cycle did not run")
            callCount.get() shouldBeGreaterOrEqualTo 2
        } finally {
            aggregator.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `리더 stop - 차순위 인스턴스가 인계하여 polling 계속`(testDB: TestTenantDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockPrefix = randomPrefix()
        val tenantId = "tenant-handover"

        val countsByNode = ConcurrentHashMap<String, AtomicInteger>()
        countsByNode["node-A"] = AtomicInteger(0)
        countsByNode["node-B"] = AtomicInteger(0)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val aggA = TenantAggregator(
            electorFactory = electorFactory(db),
            options = fastOptions("node-A", lockPrefix, tenants = listOf(tenantId), leaseTime = 2.seconds),
            aggregateFunction = { _ -> countsByNode.getValue("node-A").incrementAndGet() },
        )
        val aggB = TenantAggregator(
            electorFactory = electorFactory(db),
            options = fastOptions("node-B", lockPrefix, tenants = listOf(tenantId), leaseTime = 2.seconds),
            aggregateFunction = { _ -> countsByNode.getValue("node-B").incrementAndGet() },
        )

        try {
            // node-A 를 먼저 시작하여 초기 리더로 선점 확보
            aggA.start(scope)
            // node-A 가 최소 1번 leader 로 aggregate 를 실행할 때까지 대기.
            // 이 단계가 timeout 되면 handover 검증 자체가 의미 없으므로 명시적으로 assert.
            val nodeAReady = waitUntil(INSTANCE_TIMEOUT) {
                countsByNode.getValue("node-A").get() >= 1
            }
            nodeAReady.shouldBeTrue()

            // 이후에 node-B 시작 — node-A 가 lock 보유 중이므로 대기 상태.
            aggB.start(scope)

            val countAtStop = countsByNode.getValue("node-A").get()
            aggA.stopGracefully(2.seconds)

            // 차순위 인계 — node-B 가 lease 만료 후 락 획득
            val ok = waitUntil(INSTANCE_TIMEOUT) { countsByNode.getValue("node-B").get() >= 1 }
            ok.shouldBeTrue()
            // node-A 는 stop 이후 더 이상 증가하면 안 됨 (관대하게 +1 정도 허용 — 사이클 race)
            (countsByNode.getValue("node-A").get() <= countAtStop + 1).shouldBeTrue()
        } finally {
            aggB.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `start 후 cancelAndJoin - CancellationException 재throw 로 정상 종료`(testDB: TestTenantDB) = runSuspendIO {
        val db = setupDb(testDB)
        cleanTables(db)
        val lockPrefix = randomPrefix()

        val aggregator = TenantAggregator(
            electorFactory = electorFactory(db),
            options = fastOptions("node-cancel", lockPrefix, tenants = listOf("only")),
            aggregateFunction = { delay(50.milliseconds) },
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = aggregator.start(scope)
        delay(300.milliseconds)
        job.cancelAndJoin()
        job.isCancelled.shouldBeTrue()
        // CancellationException 이 정상 전파되어 join 후 회수되었는지 확인
        job.isCompleted.shouldBeTrue()
    }

    @Test
    fun `blank nodeId - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            TenantAggregatorOptions(nodeId = "  ", tenants = DEFAULT_TENANTS)
        }
    }

    @Test
    fun `blank lockNamePrefix - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            TenantAggregatorOptions(nodeId = "ok", tenants = DEFAULT_TENANTS, lockNamePrefix = "")
        }
    }

    @Test
    fun `tenants 빈 목록 - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            TenantAggregatorOptions(nodeId = "ok", tenants = emptyList())
        }
    }

    @Test
    fun `tenants 항목 blank - IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            TenantAggregatorOptions(nodeId = "ok", tenants = listOf("ok-1", " ", "ok-2"))
        }
    }

    @Test
    fun `start 두번 호출 - IllegalStateException`(): Unit = runBlocking {
        // factory 를 영원히 suspend 시켜 첫 번째 start 의 tenantLoop 가 elector 생성 단계에서 멈추도록 한다.
        // 이렇게 하면 factory throw / loop 종료로 인한 race 없이 두 번째 start 호출을 동기적으로 검증할 수 있다.
        val blockingFactory: suspend (String, LeaderElectionOptions) -> SuspendLeaderElector = { _, _ ->
            awaitCancellation()
        }
        val aggregator = TenantAggregator(
            electorFactory = blockingFactory,
            options = TenantAggregatorOptions(
                nodeId = "n",
                tenants = listOf("t"),
                pollInterval = 100.milliseconds,
                waitTime = 100.milliseconds,
                leaseTime = 1.seconds,
            ),
            aggregateFunction = { },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            aggregator.start(scope)
            assertFailsWith<IllegalStateException> { aggregator.start(scope) }
        } finally {
            aggregator.stopGracefully(2.seconds)
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
        }
    }

    @Test
    fun `start 동시 호출 - 정확히 하나만 성공하고 나머지는 IllegalStateException`(): Unit = runBlocking {
        // P2-1 회귀 테스트: 두 개 이상의 호출자가 거의 동시에 start() 를 호출해도
        // ReentrantLock 으로 직렬화되어 단 하나의 호출만 성공해야 한다.
        repeat(20) { iteration ->
            // factory 는 awaitCancellation 으로 무기한 suspend — start 후에도 root job 이 active 상태 유지
            val blockingFactory: suspend (String, LeaderElectionOptions) -> SuspendLeaderElector = { _, _ ->
                awaitCancellation()
            }
            val aggregator = TenantAggregator(
                electorFactory = blockingFactory,
                options = TenantAggregatorOptions(
                    nodeId = "concurrent-$iteration",
                    tenants = listOf("t"),
                    pollInterval = 50.milliseconds,
                    waitTime = 50.milliseconds,
                    leaseTime = 1.seconds,
                ),
                aggregateFunction = { },
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = CompletableDeferred<Unit>()
            val successCount = AtomicInteger(0)
            val failureCount = AtomicInteger(0)
            try {
                val callerCount = 8
                val callers = (1..callerCount).map {
                    scope.launch {
                        gate.await() // 동시 시작
                        try {
                            aggregator.start(scope)
                            successCount.incrementAndGet()
                        } catch (e: IllegalStateException) {
                            failureCount.incrementAndGet()
                        }
                    }
                }
                gate.complete(Unit)
                callers.forEach { it.join() }

                // 정확히 하나의 호출만 성공해야 함
                successCount.get() shouldBeEqualTo 1
                failureCount.get() shouldBeEqualTo callerCount - 1
            } finally {
                aggregator.stopGracefully(2.seconds)
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            }
        }
    }
}

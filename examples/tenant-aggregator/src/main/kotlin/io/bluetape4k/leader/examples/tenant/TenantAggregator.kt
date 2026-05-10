package io.bluetape4k.leader.examples.tenant

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 테넌트별 독립 leader-election 으로 멀티테넌트 집계를 수행하는 long-running 워커.
 *
 * ## 동작/계약
 *
 * - 다중 인스턴스 환경에서 동일 [TenantAggregatorOptions.lockNamePrefix] / [TenantAggregatorOptions.tenants] 를
 *   공유하는 N 개 인스턴스가 [start] 를 호출하면, **각 테넌트마다 정확히 1 인스턴스의 코루틴만**
 *   `aggregateFunction` 을 polling 한다.
 * - 락 이름은 `"${lockNamePrefix}-${tenantId}"` 으로 조립된다 (테넌트 단위 독립).
 * - 각 테넌트는 [start] 시점에 [electorFactory] 로 elector 를 1회 생성하고, 사이클마다 동일 elector 의
 *   `runIfLeader(lockName)` 를 재호출하여 `aggregateFunction` 을 실행한다 (elector 재생성 비용 회피).
 * - `aggregateFunction` 예외는 격리되어 다음 사이클은 계속 실행된다 (poison 방지).
 * - [CancellationException] 은 모든 catch 에서 즉시 re-throw 한다.
 *
 * ### LeaderGroup 미사용 이유
 *
 * `LeaderGroupElector` 는 단일 lockName 의 maxLeaders 슬롯을 공유하므로
 * "테넌트 T 가 어느 슬롯에 배정될지" 를 호출자가 제어할 수 없다. 본 집계기는
 * **테넌트별 독립 lockName** 으로 leader-election 을 수행한다 (E4 cache-warmer 와 동일 결정).
 *
 * ### Graceful Stop
 *
 * [stopGracefully] 는 모든 테넌트 코루틴을 cancel 하고 timeout 안에 join 한다.
 * 진행 중인 `aggregateFunction` 호출은 [CancellationException] 으로 인터럽트되며,
 * `runIfLeader` 가 보유한 락은 elector 가 finally 블록에서 자동 해제한다 (lease 만료 후 차순위 인수).
 *
 * ```kotlin
 * val aggregator = TenantAggregator(
 *     electorFactory = { _, options -> ExposedR2DbcSuspendLeaderElector(db, /* ... */ options) },
 *     options = TenantAggregatorOptions(
 *         nodeId = "node-A",
 *         lockNamePrefix = "tenant-aggregator:metrics",
 *         tenants = listOf("tenant-A", "tenant-B", "tenant-C"),
 *     ),
 *     aggregateFunction = { tenantId -> metricsService.aggregate(tenantId) },
 * )
 * val job = aggregator.start(applicationScope)
 * // ... shutdown ...
 * aggregator.stopGracefully()
 * ```
 *
 * @param electorFactory 락 이름 + 옵션을 받아 [SuspendLeaderElector] 를 생성하는 suspend 팩토리.
 *                       Exposed R2DBC / Mongo / Redis 등 백엔드 교체 가능 + 테스트 fake 주입.
 * @param options 집계기 동작 설정
 * @param aggregateFunction 테넌트별 polling 콜백. 예외는 격리되어 다음 사이클로 진행.
 */
class TenantAggregator(
    private val electorFactory: suspend (lockName: String, options: LeaderElectionOptions) -> SuspendLeaderElector,
    val options: TenantAggregatorOptions,
    private val aggregateFunction: suspend (tenantId: String) -> Unit,
) {

    companion object: KLogging()

    /**
     * [start] / [stopGracefully] 의 동시 호출을 직렬화하기 위한 락.
     *
     * 가상 스레드 환경에서도 안전하도록 `synchronized` 가 아닌 [ReentrantLock] 을 사용한다.
     * (워크스페이스 정책: `@Synchronized` / `synchronized {}` 사용 금지).
     */
    private val lifecycleLock = ReentrantLock()

    @Volatile
    private var rootJob: Job? = null

    /**
     * 모든 [TenantAggregatorOptions.tenants] 에 대해 별도 child coroutine 을 launch 하여 polling 시작.
     *
     * - 동일 인스턴스에서 두 번 호출 금지 (이미 실행 중이면 [IllegalStateException]).
     *   여러 호출자가 거의 동시에 호출해도 [lifecycleLock] 으로 직렬화되어 단 하나의 호출만 성공한다 (thread-safe).
     * - [scope] 가 cancel 되면 모든 테넌트 코루틴이 자동 종료.
     * - 한 테넌트 코루틴의 예외는 다른 테넌트 코루틴에 영향을 주지 않는다 ([supervisorScope] 사용).
     */
    fun start(scope: CoroutineScope): Job = lifecycleLock.withLock {
        check(rootJob == null || rootJob?.isActive != true) {
            "TenantAggregator(nodeId=${options.nodeId}) is already running"
        }
        val job = scope.launch {
            try {
                supervisorScope {
                    options.tenants.forEach { tenantId ->
                        launch { tenantLoop(tenantId) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "[${options.nodeId}] tenant aggregator root terminated unexpectedly" }
                throw e
            }
        }
        rootJob = job
        job
    }

    /**
     * 모든 테넌트 polling 을 정상 종료한다. [timeout] 안에 종료되지 않으면 강제 cancel 후 반환.
     *
     * [start] 와 동일한 [lifecycleLock] 으로 보호되어 동시 호출에도 안전하다.
     */
    suspend fun stopGracefully(timeout: Duration = 30.seconds) {
        val job = lifecycleLock.withLock { rootJob } ?: return
        try {
            withTimeoutOrNull(timeout) { job.cancelAndJoin() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] stopGracefully encountered error" }
        } finally {
            lifecycleLock.withLock {
                if (rootJob === job) rootJob = null
            }
        }
    }

    /**
     * 단일 테넌트 polling 루프.
     *
     * 1. [start] 시점에 elector 1회 생성 — 매 사이클 재생성 시 schema-init 등 무거운 작업 반복 방지.
     * 2. `runIfLeader(lockName)` 로 본 인스턴스가 리더면 [aggregateFunction] 실행, 아니면 null.
     * 3. 사이클 종료 후 [TenantAggregatorOptions.pollInterval] 만큼 대기.
     */
    private suspend fun tenantLoop(tenantId: String) {
        val lockName = "${options.lockNamePrefix}-$tenantId"
        val electionOptions = LeaderElectionOptions(
            waitTime = options.waitTime,
            leaseTime = options.leaseTime,
            nodeId = options.nodeId,
        )

        val elector: SuspendLeaderElector = try {
            electorFactory(lockName, electionOptions)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] tenant=$tenantId elector 생성 실패 — 루프 종료" }
            return
        }

        log.info { "[${options.nodeId}] tenant=$tenantId polling 시작 (lockName=$lockName)" }

        while (currentCoroutineContext().isActive) {
            try {
                val ran = elector.runIfLeader(lockName) {
                    log.debug { "[${options.nodeId}] tenant=$tenantId 리더 선출 — aggregate 실행" }
                    runAggregate(tenantId)
                }
                if (ran == null) {
                    log.debug { "[${options.nodeId}] tenant=$tenantId 비리더 — 다음 사이클 대기" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // runIfLeader 자체 예외 (백엔드 장애 등) — 다음 사이클 재시도
                log.warn(e) { "[${options.nodeId}] tenant=$tenantId 리더 선출 사이클 예외 — 다음 사이클 재시도" }
            }
            delay(options.pollInterval)
        }

        log.info { "[${options.nodeId}] tenant=$tenantId polling 종료" }
    }

    /**
     * `aggregateFunction` 실행 — 예외 격리 (poison 방지).
     */
    private suspend fun runAggregate(tenantId: String) {
        try {
            aggregateFunction(tenantId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "[${options.nodeId}] tenant=$tenantId aggregate 예외 격리 — 다음 사이클 계속" }
        }
    }
}

package io.bluetape4k.leader.examples.warmer

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * 파티션별 독립 leader-election 으로 캐시를 워밍하는 워커.
 *
 * ## 동작/계약
 *
 * - 다중 인스턴스 환경에서 동일 [CachePartitionWarmerOptions.lockNamePrefix] / [partitions] 를
 *   공유하는 N 개 워머가 [warmAll] 을 호출하면, **각 partition 마다 정확히 1개 인스턴스만**
 *   [warmFunction] 을 실행한다.
 * - 락 이름은 `"${lockNamePrefix}-${partitionId}"` 으로 조립된다 (파티션 단위 독립).
 * - 비리더 호출은 [WarmResult.skipped] 에 기록되며 throw 하지 않는다 (ShedLock 호환 동작).
 * - [warmFunction] 예외는 격리되어 [WarmResult.failed] 에 기록되고, 다음 partition 처리는 계속.
 * - [CancellationException] 은 모든 catch 에서 즉시 re-throw 한다 (coroutine cancellation 무결성).
 *
 * ### 왜 LeaderGroup 이 아닌가
 *
 * Hazelcast `LeaderGroupElector` 는 단일 lockName 의 maxLeaders 슬롯을 공유하므로 슬롯 ↔ partition
 * 매핑을 호출자가 강제할 수 없다. 본 워머는 partition 별 별도 lockName 으로 leader-election 하여
 * "partition P 에 대해서는 정확히 1 인스턴스" 계약을 직접 만족시킨다.
 *
 * ```kotlin
 * val warmer = CachePartitionWarmer(
 *     electorFactory = { _, options -> HazelcastLeaderElector(hazelcast, options) },
 *     options = CachePartitionWarmerOptions(
 *         nodeId = "node-A",
 *         lockNamePrefix = "warmer:product",
 *         partitions = listOf("region-asia", "region-eu"),
 *     ),
 *     warmFunction = { partitionId -> productCache.preload(partitionId) },
 * )
 * val result = warmer.warmAll()
 * ```
 *
 * @param electorFactory 락 이름 + 옵션을 받아 [LeaderElector] 인스턴스를 생성하는 팩토리.
 *                       Hazelcast / Redis / Mongo 등 백엔드 교체 가능 + 테스트 fake 주입.
 * @param options 워머 동작 설정
 * @param warmFunction 파티션 워밍 콜백. 예외는 격리되어 [WarmResult.failed] 로 수집.
 */
class CachePartitionWarmer(
    private val electorFactory: (lockName: String, options: LeaderElectionOptions) -> LeaderElector,
    val options: CachePartitionWarmerOptions,
    private val warmFunction: (partitionId: String) -> Unit,
) {

    companion object: KLogging()

    /**
     * 모든 [CachePartitionWarmerOptions.partitions] 에 대해 leader-election 을 시도하고
     * 리더로 선출된 partition 만 워밍한다.
     *
     * 본 메서드는 partition 을 순차 처리한다 (concurrent execution 미지원).
     * 호출자가 동시 실행이 필요하면 외부에서 thread-pool / coroutine 으로 병렬 호출하면 된다.
     *
     * @return 본 인스턴스의 워밍 결과 [WarmResult]
     */
    fun warmAll(): WarmResult {
        val warmed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()

        options.partitions.forEach { partitionId ->
            val lockName = "${options.lockNamePrefix}-$partitionId"
            val electionOptions = LeaderElectionOptions(
                waitTime = options.waitTime,
                leaseTime = options.leaseTime,
                nodeId = options.nodeId,
            )
            val elector = electorFactory(lockName, electionOptions)

            log.debug { "[${options.nodeId}] partition=$partitionId lockName=$lockName 리더 선출 시도" }

            val outcome: WarmOutcome = try {
                val ran = elector.runIfLeader(lockName) {
                    log.info { "[${options.nodeId}] partition=$partitionId 리더 선출 — 워밍 시작" }
                    warmFunction(partitionId)
                    log.info { "[${options.nodeId}] partition=$partitionId 워밍 완료" }
                    WarmOutcome.Warmed
                }
                ran ?: WarmOutcome.Skipped
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e::class.qualifiedName ?: "unknown"
                log.warn(e) { "[${options.nodeId}] partition=$partitionId 워밍 실패 — failed 기록 후 다음 partition 진행" }
                WarmOutcome.Failed(msg)
            }

            when (outcome) {
                WarmOutcome.Warmed -> warmed += partitionId
                WarmOutcome.Skipped -> {
                    skipped += partitionId
                    log.info { "[${options.nodeId}] partition=$partitionId 리더 선출 실패 — skip" }
                }
                is WarmOutcome.Failed -> failed[partitionId] = outcome.message
            }
        }

        return WarmResult(
            nodeId = options.nodeId,
            warmed = warmed.toList(),
            skipped = skipped.toList(),
            failed = failed.toMap(),
        )
    }

    private sealed interface WarmOutcome {
        data object Warmed: WarmOutcome
        data object Skipped: WarmOutcome
        data class Failed(val message: String): WarmOutcome
    }
}

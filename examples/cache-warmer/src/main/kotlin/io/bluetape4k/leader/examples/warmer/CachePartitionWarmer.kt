package io.bluetape4k.leader.examples.warmer

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import kotlin.coroutines.cancellation.CancellationException

/**
 * `CachePartitionWarmer`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property electorFactory example workflow 계약에서 사용하는 속성입니다.
 * @property options example workflow 계약에서 사용하는 속성입니다.
 * @property warmFunction example workflow 계약에서 사용하는 속성입니다.
 */
class CachePartitionWarmer(
    private val electorFactory: (lockName: String, options: LeaderElectionOptions) -> LeaderElector,
    val options: CachePartitionWarmerOptions,
    private val warmFunction: (partitionId: String) -> Unit,
) {

    companion object: KLogging()

    /**
     * `warmAll` 호출은 example workflow 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
        /**
         * `Failed`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
         *
         * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
         * @property message example workflow 계약에서 `message` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         */
        data class Failed(val message: String): WarmOutcome
    }
}

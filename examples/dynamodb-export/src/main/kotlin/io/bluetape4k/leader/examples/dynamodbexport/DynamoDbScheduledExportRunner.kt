package io.bluetape4k.leader.examples.dynamodbexport

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * `DynamoDbScheduledExportRunner`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property options example workflow 계약에서 사용하는 속성입니다.
 * @property elector example workflow 계약에서 사용하는 속성입니다.
 * @property exportTable example workflow 계약에서 사용하는 속성입니다.
 */
class DynamoDbScheduledExportRunner(
    val options: DynamoDbExportRunnerOptions,
    private val elector: SuspendLeaderElector,
    private val exportTable: DynamoDbExportTable,
) {

    companion object: KLogging()

    suspend fun runOnce(
        batchId: String,
        exportJob: suspend () -> String,
    ): DynamoDbExportReport {
        batchId.requireNotBlank("batchId")
        val startedAt = System.nanoTime()

        val exportId = try {
            elector.runIfLeader(options.lockName) {
                val summary = exportJob().also { it.requireNotBlank("summary") }
                val record = DynamoDbExportRecord(
                    exportId = "${batchId}-${options.nodeId}-${Base58.randomString(8)}",
                    batchId = batchId,
                    nodeId = options.nodeId,
                    createdAt = Instant.now(),
                    summary = summary,
                )
                exportTable.put(record)
                log.info { "[${options.nodeId}] wrote DynamoDB export ${record.exportId}" }
                record.exportId
            }
        } catch (e: CancellationException) {
            throw e
        }

        val elapsed = (System.nanoTime() - startedAt).nanoseconds
        return DynamoDbExportReport(
            nodeId = options.nodeId,
            batchId = batchId,
            status = if (exportId == null) DynamoDbExportStatus.SKIPPED else DynamoDbExportStatus.EXPORTED,
            exportId = exportId,
            elapsed = elapsed,
        )
    }
}

/**
 * `DynamoDbExportRunnerOptions`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockName example workflow 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaderOptions example workflow 계약에서 `leaderOptions` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class DynamoDbExportRunnerOptions(
    val nodeId: String,
    val lockName: String,
    val leaderOptions: LeaderElectionOptions = LeaderElectionOptions(
        waitTime = 150.milliseconds,
        leaseTime = 5.seconds,
    ),
): Serializable {

    init {
        nodeId.requireNotBlank("nodeId")
        lockName.requireNotBlank("lockName")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

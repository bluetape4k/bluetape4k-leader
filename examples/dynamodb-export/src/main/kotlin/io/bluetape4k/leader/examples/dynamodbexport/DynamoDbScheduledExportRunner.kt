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
 * Runs a scheduled export job only on the node elected through DynamoDB.
 *
 * ## Contract
 *
 * The runner delegates leadership to [SuspendLeaderElector]. When leadership is
 * acquired, [runOnce] executes the caller's export job and writes one export
 * record to [exportTable]. When another node holds the same lock, [runOnce]
 * returns [DynamoDbExportStatus.SKIPPED] without throwing.
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

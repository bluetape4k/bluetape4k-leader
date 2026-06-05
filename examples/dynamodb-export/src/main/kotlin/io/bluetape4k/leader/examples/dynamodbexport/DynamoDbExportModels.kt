package io.bluetape4k.leader.examples.dynamodbexport

import java.io.Serializable
import java.time.Instant
import kotlin.time.Duration

enum class DynamoDbExportStatus {
    EXPORTED,
    SKIPPED,
}

data class DynamoDbExportReport(
    val nodeId: String,
    val batchId: String,
    val status: DynamoDbExportStatus,
    val exportId: String?,
    val elapsed: Duration,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class DynamoDbExportRecord(
    val exportId: String,
    val batchId: String,
    val nodeId: String,
    val createdAt: Instant,
    val summary: String,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

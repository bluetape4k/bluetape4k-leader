package io.bluetape4k.leader.examples.dynamodbexport

import java.io.Serializable
import java.time.Instant
import kotlin.time.Duration

enum class DynamoDbExportStatus {
    EXPORTED,
    SKIPPED,
}

/**
 * `DynamoDbExportReport`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property batchId example workflow 계약에서 `batchId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status example workflow 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property exportId example workflow 계약에서 `exportId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property elapsed example workflow 계약에서 `elapsed` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
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

/**
 * `DynamoDbExportRecord`는 example workflow에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property exportId example workflow 계약에서 `exportId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property batchId example workflow 계약에서 `batchId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property nodeId example workflow 계약에서 `nodeId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property createdAt example workflow 계약에서 `createdAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property summary example workflow 계약에서 `summary` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
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

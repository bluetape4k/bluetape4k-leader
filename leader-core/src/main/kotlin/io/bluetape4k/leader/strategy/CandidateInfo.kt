package io.bluetape4k.leader.strategy

import java.time.Instant
import java.io.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `CandidateInfo`는 전략 선출 후보 노드의 metadata입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property nodeId 상태 조회와 audit에 노출되는 노드 또는 인스턴스 식별자입니다.
 * @property registeredAt `registeredAt` 호출 또는 상태 계산에 필요한 값입니다.
 * @property lastStartTime `lastStartTime` 호출 또는 상태 계산에 필요한 값입니다.
 * @property lastCompletionTime `lastCompletionTime` 호출 또는 상태 계산에 필요한 값입니다.
 * @property successCount `successCount` 호출 또는 상태 계산에 필요한 값입니다.
 * @property failureCount `failureCount` 호출 또는 상태 계산에 필요한 값입니다.
 * @property metadata 호출자가 제공한 key-value audit context입니다. recorder 계층에서 크기와 길이가 제한됩니다.
 */
data class CandidateInfo(
    val nodeId: String,
    val registeredAt: Instant = Instant.now(),
    val lastStartTime: Instant? = null,
    val lastCompletionTime: Instant? = null,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
) : Serializable {

    /**
     * `idleDuration` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val idleDuration: Duration
        get() = lastCompletionTime?.let { (Instant.now().toEpochMilli() - it.toEpochMilli()).milliseconds }
            ?: (Instant.now().toEpochMilli() - registeredAt.toEpochMilli()).milliseconds

    /**
     * `successRate` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val successRate: Double
        get() = if (successCount + failureCount == 0L) 0.0
                else successCount.toDouble() / (successCount + failureCount)

    /**
     * `totalCount` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val totalCount: Long get() = successCount + failureCount

    /**
     * `withResult` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param result 후보 작업의 성공 또는 실패 결과입니다.
     * @param completionTime 작업 완료가 기록된 시각입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun withResult(result: CandidateResult, completionTime: Instant = Instant.now()): CandidateInfo =
        when (result) {
            CandidateResult.SUCCESS -> copy(
                lastCompletionTime = completionTime,
                successCount = successCount + 1,
            )
            CandidateResult.FAILURE -> copy(
                lastCompletionTime = completionTime,
                failureCount = failureCount + 1,
            )
        }

    companion object {
        private const val serialVersionUID = 1L
    }
}

package io.bluetape4k.leader.strategy

import java.io.Serializable
import java.time.Duration
import java.time.Instant

/**
 * 리더 선출 후보 노드의 메타데이터를 담는 데이터 클래스입니다.
 *
 * [idleDuration] 은 마지막 완료 이후 경과 시간을 나타내며, 실행 이력이 없으면 [registeredAt] 부터 계산합니다.
 *
 * @property nodeId 노드 식별자. 노드 인스턴스 생성 시 UUID v7으로 할당하고 재사용해야 합니다.
 * @property registeredAt 후보 등록 시각
 * @property lastStartTime 마지막 작업 시작 시각
 * @property lastCompletionTime 마지막 작업 완료 시각
 * @property successCount 누적 성공 횟수
 * @property failureCount 누적 실패 횟수
 * @property metadata 확장 메타데이터
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
     * 마지막 완료 이후 경과 시간.
     * 완료 이력이 없으면 [registeredAt] 부터 계산합니다 (미실행 노드 = 등록 이후 전체 경과 시간).
     */
    val idleDuration: Duration
        get() = lastCompletionTime?.let { Duration.between(it, Instant.now()) }
            ?: Duration.between(registeredAt, Instant.now())

    /** 성공률 (0.0 ~ 1.0). 이력 없으면 0.0. */
    val successRate: Double
        get() = if (successCount + failureCount == 0L) 0.0
                else successCount.toDouble() / (successCount + failureCount)

    /** 총 실행 횟수 */
    val totalCount: Long get() = successCount + failureCount

    /**
     * 작업 결과를 반영한 새 [CandidateInfo] 를 반환합니다.
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
}

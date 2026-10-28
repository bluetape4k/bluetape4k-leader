package io.bluetape4k.leader.strategy

import java.io.Serializable
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `CandidateInfo`는 전략 선출에서 후보 노드의 실행 이력과 caller가 제공한 metadata를 전달합니다.
 *
 * @property nodeId 후보 노드를 구분하는 식별자입니다.
 * @property registeredAt 후보를 등록한 시각입니다. 기본값은 객체를 생성한 시각입니다.
 * @property lastStartTime 마지막 작업 시작 시각입니다. 시작 이력이 없으면 `null`입니다.
 * @property lastCompletionTime 마지막 작업 완료 시각입니다. 완료 이력이 없으면 `null`입니다.
 * @property successCount 기록된 성공 횟수입니다. 기본값은 `0`이며 이 클래스는 입력 범위를 검증하지 않습니다.
 * @property failureCount 기록된 실패 횟수입니다. 기본값은 `0`이며 이 클래스는 입력 범위를 검증하지 않습니다.
 * @property metadata caller가 후보 등록·갱신 때 제공하는 key-value 문맥입니다. 전략 elector의 메모리 레지스트리에 보관하고 `listCandidates`가
 * 반환합니다. 자동 마스킹하지 않으므로 민감정보를 넣지 마세요.
 */
data class CandidateInfo(
    val nodeId: String,
    val registeredAt: Instant = Instant.now(),
    val lastStartTime: Instant? = null,
    val lastCompletionTime: Instant? = null,
    val successCount: Long = 0L,
    val failureCount: Long = 0L,
    val metadata: Map<String, String> = emptyMap(),
): Serializable {

    /**
     * 마지막 완료 시각부터 현재까지의 경과 시간입니다. 완료 이력이 없으면 등록 시각부터 계산하며 밀리초 단위로 잘립니다. 기준 시각이 미래이면 음수일 수 있습니다.
     */
    val idleDuration: Duration
        get() = lastCompletionTime?.let { (Instant.now().toEpochMilli() - it.toEpochMilli()).milliseconds }
            ?: (Instant.now().toEpochMilli() - registeredAt.toEpochMilli()).milliseconds

    /**
     * 전체 결과 중 성공 결과의 비율입니다. 성공 횟수와 실패 횟수의 합이 `0`이면 `0.0`을 반환합니다.
     */
    val successRate: Double
        get() =
            if (successCount + failureCount == 0L) 0.0
            else successCount.toDouble() / (successCount + failureCount)

    /** 성공과 실패로 기록된 결과 횟수의 합입니다. */
    val totalCount: Long get() = successCount + failureCount

    /**
     * 작업 결과를 반영한 새 후보 정보를 반환합니다. 기존 인스턴스는 변경하지 않습니다.
     * @param result 후보 작업의 성공 또는 실패 결과입니다.
     * @param completionTime 결과가 완료된 시각입니다. 기본값은 호출 시각입니다.
     * @return 완료 시각과 해당 결과 횟수를 갱신한 새 정보입니다.
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

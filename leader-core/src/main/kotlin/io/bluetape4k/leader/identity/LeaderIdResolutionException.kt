package io.bluetape4k.leader.identity

/**
 * `LeaderIdResolutionException` 선언은 leader election 계약에서 사용되는 class입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 * @property message `message` 호출 또는 상태 계산에 필요한 값입니다.
 * @property cause 실패 결과를 만든 원본 예외입니다.
 */
class LeaderIdResolutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

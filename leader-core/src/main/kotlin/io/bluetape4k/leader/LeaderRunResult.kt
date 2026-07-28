package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import java.io.Serializable

/**
 * `LeaderRunResult`는 leader election 실행 결과를 null ambiguity 없이 표현하는 sealed result입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
sealed interface LeaderRunResult<out T>: Serializable {

    companion object : KLogging()

    /**
     * `Elected` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property value leadership 획득 후 실행한 작업의 반환값입니다.
     * @property leaderId audit에 기록할 leader identity입니다.
     */
    data class Elected<out T> @JvmOverloads constructor(
        val value: T?,
        val leaderId: String? = null,
    ) : LeaderRunResult<T> {
        companion object {
            private const val serialVersionUID: Long = 5711634040242986115L
        }
    }

    /**
     * `Skipped` 선언은 leader election 계약에서 사용되는 object입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     */
    data object Skipped : LeaderRunResult<Nothing>

    /**
     * `ActionFailed` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property cause 실패 결과를 만든 원본 예외입니다.
     */
    data class ActionFailed(val cause: Throwable) : LeaderRunResult<Nothing> {
        companion object {
            private const val serialVersionUID: Long = -1428070323206111594L
        }
    }
}

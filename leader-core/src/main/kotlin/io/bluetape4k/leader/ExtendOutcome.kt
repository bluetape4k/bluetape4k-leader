package io.bluetape4k.leader

import java.io.Serializable
import java.time.Instant

/**
 * `ExtendOutcome` 선언은 leader election 계약에서 사용되는 interface입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
sealed interface ExtendOutcome : Serializable {

    /**
     * `Extended` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property observedExpireAt `observedExpireAt` 호출 또는 상태 계산에 필요한 값입니다.
     */
    data class Extended(val observedExpireAt: Instant) : ExtendOutcome {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * `NotHeld` 선언은 leader election 계약에서 사용되는 object입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     */
    data object NotHeld : ExtendOutcome {
        private const val serialVersionUID = 1L
    }

    /**
     * `WrongThread` 선언은 leader election 계약에서 사용되는 object입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     */
    data object WrongThread : ExtendOutcome {
        private const val serialVersionUID = 1L
    }

    /**
     * `BackendError` 선언은 leader election 계약에서 사용되는 data class입니다.
     *
     * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
     * @property cause 실패 결과를 만든 원본 예외입니다.
     */
    data class BackendError(val cause: Exception) : ExtendOutcome {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * `isExtended` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val isExtended: Boolean get() = this is Extended
}

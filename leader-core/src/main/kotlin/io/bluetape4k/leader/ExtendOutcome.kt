package io.bluetape4k.leader

import java.io.Serializable
import java.time.Instant

/**
 * lease 연장 요청이 완료됐는지, 실행할 수 없었는지 또는 backend 오류를 만났는지 나타냅니다.
 */
sealed interface ExtendOutcome: Serializable {

    /**
     * backend가 연장을 완료했습니다.
     * @property observedExpireAt backend가 보고한 새 lease 만료 시각
     */
    data class Extended(val observedExpireAt: Instant): ExtendOutcome {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * watchdog 또는 bounded operation queue가 포화되어 extension을 시작하지 못한 상태입니다.
     */
    data object Rejected: ExtendOutcome {
        private fun readResolve(): Any = Rejected
        private const val serialVersionUID = 1L
    }

    /**
     * 현재 handle이 해당 lock 또는 slot을 보유하지 않아 연장하지 않았습니다.
     */
    data object NotHeld: ExtendOutcome {
        private fun readResolve(): Any = NotHeld
        private const val serialVersionUID = 1L
    }

    /**
     * 현재 thread가 lock 소유 thread가 아니어서 연장하지 않았습니다.
     */
    data object WrongThread: ExtendOutcome {
        private fun readResolve(): Any = WrongThread
        private const val serialVersionUID = 1L
    }

    /**
     * backend가 연장 요청을 처리하는 중 오류를 반환했습니다.
     * @property cause backend가 던진 원본 예외
     */
    data class BackendError(val cause: Exception): ExtendOutcome {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * 연장이 완료된 `Extended` 결과인지 확인합니다.
     */
    val isExtended: Boolean get() = this is Extended
}

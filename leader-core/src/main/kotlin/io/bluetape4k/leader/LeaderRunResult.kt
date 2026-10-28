package io.bluetape4k.leader

import io.bluetape4k.logging.KLogging
import java.io.Serializable

/**
 * lock 획득 성공, contention skip, 사용자 action 실패를 서로 다른 타입으로 구분합니다.
 */
sealed interface LeaderRunResult<out T>: Serializable {

    companion object: KLogging()

    /**
     * lock 획득에 성공해 action을 실행한 결과입니다.
     * @property value action 반환값. action이 null을 반환할 수 있습니다.
     * @property leaderId 결과와 함께 제공할 leader ID. overload에 따라 null일 수 있습니다.
     */
    data class Elected<out T> @JvmOverloads constructor(
        val value: T?,
        val leaderId: String? = null,
    ): LeaderRunResult<T> {
        companion object {
            private const val serialVersionUID: Long = 5711634040242986115L
        }
    }

    /**
     * lock contention으로 획득하지 못해 action을 실행하지 않았습니다.
     */
    data object Skipped: LeaderRunResult<Nothing> {
        private fun readResolve(): Any = Skipped
    }

    /**
     * lock 획득 후 action이 예외를 던졌습니다. cancellation은 이 결과 대신 호출자에게 전파됩니다.
     * @property cause action에서 발생한 원본 예외
     */
    data class ActionFailed(val cause: Throwable): LeaderRunResult<Nothing> {
        companion object {
            private const val serialVersionUID: Long = -1428070323206111594L
        }
    }
}

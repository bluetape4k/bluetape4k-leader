package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `ExtendDelegate` 선언은 leader election 계약에서 사용되는 interface입니다.
 *
 * API 이름과 `lock`, `lease`, `leader`, `slot`, `audit` 용어는 코드 계약과 동일하게 유지합니다.
 */
interface ExtendDelegate {

    fun extend(lockAtMostFor: Duration): ExtendOutcome

    /**
     * `extendSuspend` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @param lockAtMostFor `lockAtMostFor` 호출 또는 상태 계산에 필요한 값입니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = extend(lockAtMostFor)

    /**
     * `isHeld` 호출은 leader election 계약의 일부 동작을 수행합니다.
     *
     * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
     * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
     */
    fun isHeld(): Boolean

    /**
     * `lastExtendDeadline` 값은 leader election 계약에서 노출되는 상태 또는 설정 항목입니다.
     */
    val lastExtendDeadline: AtomicReference<Instant>
}

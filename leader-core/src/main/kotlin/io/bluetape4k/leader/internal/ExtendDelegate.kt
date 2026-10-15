package io.bluetape4k.leader.internal

import io.bluetape4k.leader.ExtendOutcome
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * lock handle의 lease를 연장하고 현재 소유 여부 및 마지막으로 관찰한 만료 시각을 제공합니다.
 */
interface ExtendDelegate {

    /** @param lockAtMostFor 연장 뒤에도 넘지 않을 최대 lock lease 길이 */
    fun extend(lockAtMostFor: Duration): ExtendOutcome

    /**
     * 기본 구현은 blocking [extend]를 호출합니다. suspend backend는 이 함수를 재정의합니다.
     * @param lockAtMostFor 연장 뒤에도 넘지 않을 최대 lock lease 길이
     * @return 연장 여부와 backend 오류를 구분하는 결과
     */
    suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome = extend(lockAtMostFor)

    /**
     * 현재 실행 중인 lock handle이 backend lock을 소유하는지 확인합니다.
     */
    fun isHeld(): Boolean

    /**
     * 마지막 성공한 연장에서 backend가 보고한 lease 만료 시각입니다. 아직 성공한 연장이 없으면 구현별 초기값을 가집니다.
     */
    val lastExtendDeadline: AtomicReference<Instant>
}

package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot

/** `LeaderSlot`에 대한 route 실행 권한을 판단하는 함수형 계약입니다. */
fun interface LeaderRouteAuthority {

    /** slot의 lock 이름과 leader ID를 평가해 허용ㆍ비leaderㆍ사용 불가 결정을 반환합니다. */
    fun evaluate(slot: LeaderSlot): LeaderRouteDecision
}

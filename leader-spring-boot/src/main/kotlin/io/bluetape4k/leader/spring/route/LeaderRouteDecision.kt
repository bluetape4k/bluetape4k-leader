package io.bluetape4k.leader.spring.route

/** route guard가 반환하는 세 가지 authority 결정입니다. */
sealed interface LeaderRouteDecision {

    /** 현재 slot이 leader로 확인되어 route 실행을 허용합니다. */
    data object Allowed: LeaderRouteDecision

    /** 현재 slot의 leader ID가 state와 일치하지 않아 route 실행을 거부합니다. */
    data object NotLeader: LeaderRouteDecision

    /** backend 또는 authority 평가 실패로 판단할 수 없어 route 실행을 거부합니다. */
    data object Unavailable: LeaderRouteDecision
}

package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot

/**
 * `interface` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun interface LeaderRouteAuthority {

    /**
     * `evaluate` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun evaluate(slot: LeaderSlot): LeaderRouteDecision
}

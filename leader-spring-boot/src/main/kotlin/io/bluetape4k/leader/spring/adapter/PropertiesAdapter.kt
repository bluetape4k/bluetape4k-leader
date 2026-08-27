package io.bluetape4k.leader.spring.adapter

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.spring.LeaderProperties
import kotlin.time.toKotlinDuration

/**
 * `PropertiesAdapter`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
internal object PropertiesAdapter {

    /**
     * `toCommonElection` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun toCommonElection(props: LeaderProperties): LeaderElectionOptions =
        LeaderElectionOptions(
            waitTime = props.waitTime.toKotlinDuration(),
            leaseTime = props.leaseTime.toKotlinDuration(),
        )

    /**
     * `toCommonGroup` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun toCommonGroup(props: LeaderProperties): LeaderGroupElectionOptions =
        LeaderGroupElectionOptions(
            maxLeaders = props.group.maxLeaders,
            waitTime = props.group.waitTime.toKotlinDuration(),
            leaseTime = props.group.leaseTime.toKotlinDuration(),
            useDbTime = props.group.useDbTime,
        )
}

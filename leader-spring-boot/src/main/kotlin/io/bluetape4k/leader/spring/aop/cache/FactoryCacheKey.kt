package io.bluetape4k.leader.spring.aop.cache

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * `FactoryCacheKey`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property factoryBeanName Spring Boot integration 계약에서 `factoryBeanName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property options Spring Boot integration 계약에서 `options` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class FactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderElectionOptions,
)

/**
 * `GroupFactoryCacheKey`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property factoryBeanName Spring Boot integration 계약에서 `factoryBeanName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property options Spring Boot integration 계약에서 `options` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class GroupFactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderGroupElectionOptions,
)

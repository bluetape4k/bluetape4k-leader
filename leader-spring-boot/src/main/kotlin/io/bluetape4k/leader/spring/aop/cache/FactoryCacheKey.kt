package io.bluetape4k.leader.spring.aop.cache

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * AOP 어드바이스의 [io.bluetape4k.leader.LeaderElector] 캐싱 키.
 *
 * ## [Codex C1][R-26] cross-backend collision 방지
 * `factoryBeanName` 을 키에 포함하여 동일 옵션이라도 factory 빈이 다르면 별도 인스턴스로 캐싱.
 * 다중 백엔드 (Redisson + Lettuce 동시 구성) 환경에서 옵션만으로 캐싱하면 잘못된 backend 인스턴스
 * 재사용 위험을 회피한다.
 *
 * @property factoryBeanName Spring [io.bluetape4k.leader.LeaderElectorFactory] 빈 이름
 * @property options Aspect 가 어노테이션에서 추출한 [LeaderElectionOptions]
 */
data class FactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderElectionOptions,
)

/**
 * AOP 어드바이스의 [io.bluetape4k.leader.LeaderGroupElector] 캐싱 키.
 *
 * @property factoryBeanName Spring [io.bluetape4k.leader.LeaderGroupElectorFactory] 빈 이름
 * @property options Aspect 가 어노테이션에서 추출한 [LeaderGroupElectionOptions]
 */
data class GroupFactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderGroupElectionOptions,
)

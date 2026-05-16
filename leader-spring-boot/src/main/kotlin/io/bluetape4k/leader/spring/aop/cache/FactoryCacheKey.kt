package io.bluetape4k.leader.spring.aop.cache

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * Cache key for [io.bluetape4k.leader.LeaderElector] instances used in AOP advice.
 *
 * ## [Codex C1][R-26] Cross-backend collision prevention
 * Including `factoryBeanName` in the key ensures that even with identical options, different factory beans
 * produce separate cached instances. This prevents reuse of the wrong backend instance when multiple backends
 * (e.g. Redisson + Lettuce) are configured simultaneously.
 *
 * @property factoryBeanName Spring [io.bluetape4k.leader.LeaderElectorFactory] bean name
 * @property options [LeaderElectionOptions] extracted from the annotation by the Aspect
 */
data class FactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderElectionOptions,
)

/**
 * Cache key for [io.bluetape4k.leader.LeaderGroupElector] instances used in AOP advice.
 *
 * @property factoryBeanName Spring [io.bluetape4k.leader.LeaderGroupElectorFactory] bean name
 * @property options [LeaderGroupElectionOptions] extracted from the annotation by the Aspect
 */
data class GroupFactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderGroupElectionOptions,
)

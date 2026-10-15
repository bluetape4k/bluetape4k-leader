package io.bluetape4k.leader.spring.aop.cache

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import java.io.Serializable

/**
 * 단일 leader advice에서 elector factory를 재사용하기 위한 cache key입니다.
 *
 * @property factoryBeanName 사용할 `LeaderElectorFactory` bean 이름입니다.
 * @property options 해당 factory 생성에 영향을 주는 단일 leader 옵션 전체입니다.
 */
data class FactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderElectionOptions,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * group leader advice에서 elector factory를 재사용하기 위한 cache key입니다.
 *
 * @property factoryBeanName 사용할 `LeaderGroupElectorFactory` bean 이름입니다.
 * @property options 해당 factory 생성에 영향을 주는 group leader 옵션 전체입니다.
 */
data class GroupFactoryCacheKey(
    val factoryBeanName: String,
    val options: LeaderGroupElectionOptions,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

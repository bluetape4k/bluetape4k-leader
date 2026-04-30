package io.bluetape4k.leader.spring.config

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.spring.properties.LeaderElectionProperties

/**
 * 리더 선출 자동 구성 공통 지원 클래스입니다.
 *
 * Spring Boot 3/4 각 버전의 `@AutoConfiguration` 클래스에서 상속하여
 * Boot 버전 독립 공통 옵션 변환 로직을 재사용합니다.
 *
 * Spring 어노테이션을 포함하지 않으므로 Boot 버전에 독립적입니다.
 * `@AutoConfiguration`, `@EnableConfigurationProperties` 등은 하위 클래스에서 선언합니다.
 *
 * ```kotlin
 * // Boot 3 예시
 * @AutoConfiguration
 * @EnableConfigurationProperties(BootLeaderElectionProperties::class)
 * class LeaderElectionAutoConfiguration(
 *     private val props: BootLeaderElectionProperties,
 * ) : LeaderElectionConfigSupport() {
 *     override fun properties() = props
 * }
 * ```
 */
abstract class LeaderElectionConfigSupport {

    /**
     * 현재 구성된 [LeaderElectionProperties]를 반환합니다.
     *
     * 하위 클래스에서 `@ConfigurationProperties` Bean을 주입받아 구현합니다.
     */
    protected abstract fun properties(): LeaderElectionProperties

    /**
     * [LeaderElectionProperties]로부터 [LeaderElectionOptions]를 생성합니다.
     */
    protected fun electionOptions(): LeaderElectionOptions =
        properties().toOptions()

    /**
     * [LeaderElectionProperties.group]으로부터 [LeaderGroupElectionOptions]를 생성합니다.
     */
    protected fun groupElectionOptions(): LeaderGroupElectionOptions =
        properties().group.toOptions()
}

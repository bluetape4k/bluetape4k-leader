package io.bluetape4k.leader.spring.scheduling

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * YAML로 선언한 `@Scheduled` leader policy 설정을 보관합니다.
 *
 * 이 모델은 기존 `LeaderProperties`와 분리되어 있으며, 활성화하지 않으면
 * Spring scheduling 동작에 영향을 주지 않습니다.
 */
@ConfigurationProperties(prefix = LeaderScheduledPolicyProperties.PREFIX)
data class LeaderScheduledPolicyProperties(
    val enabled: Boolean = false,
    val policies: List<Policy> = emptyList(),
) : Serializable {

    /** 하나의 `beanName#methodName` scheduled method에 적용할 leader policy입니다. */
    data class Policy(
        val selector: String = "",
        val name: String = "",
        val waitTime: Duration? = null,
        val leaseTime: Duration? = null,
        val minLeaseTime: Duration = Duration.ZERO,
        val bean: String = "",
        val autoExtend: Boolean = false,
        val streamBounded: Boolean = false,
        val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.INHERIT,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        const val PREFIX: String = "bluetape4k.leader.scheduling"
    }
}

package io.bluetape4k.leader.spring.properties

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireLe
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * Spring Actuator management action의 명시적 opt-in과 bounded timeout 설정입니다.
 *
 * action registry의 concurrency, queue, registration, cleanup cap은 core의 검증된
 * 기본값을 그대로 사용합니다. 따라서 이 설정은 기존 `LeaderProperties` ABI를
 * 확장하지 않고 write surface만 별도로 켭니다.
 */
@ConfigurationProperties(prefix = "management.endpoint.leader-election.actions")
data class LeaderManagementActionProperties(
    /** `leaderElectionActions` write endpoint를 만들지 여부입니다. */
    val enabled: Boolean = false,
    /** 한 번의 release action에 허용하는 bounded timeout입니다. */
    val timeout: Duration = DEFAULT_TIMEOUT,
) : Serializable {

    init {
        timeout.requireGt(Duration.ZERO, "management action timeout")
        timeout.requireLe(MAX_TIMEOUT, "management action timeout")
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val MAX_TIMEOUT: Duration = Duration.ofSeconds(30)

        private const val serialVersionUID = 1L
    }
}

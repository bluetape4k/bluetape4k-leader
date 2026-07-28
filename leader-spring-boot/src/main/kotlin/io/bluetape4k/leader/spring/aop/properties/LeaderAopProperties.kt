package io.bluetape4k.leader.spring.aop.properties

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * `LeaderAopProperties`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property strict Spring Boot integration 계약에서 `strict` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property failureMode Spring Boot integration 계약에서 `failureMode` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property defaultWaitTime Spring Boot integration 계약에서 `defaultWaitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property defaultLeaseTime Spring Boot integration 계약에서 `defaultLeaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockNamePrefix Spring Boot integration 계약에서 `lockNamePrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property metrics Spring Boot integration 계약에서 `metrics` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property spel Spring Boot integration 계약에서 `spel` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
@ConfigurationProperties(prefix = LeaderAopProperties.PREFIX)
/**
 * `LeaderAopProperties`는 Spring Boot integration에서 사용하는 설정, 상태, 또는 예제 workflow 값을 담는 모델입니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, route guard, metric, example intent를 문서화합니다.
 * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property strict Spring Boot integration 계약에서 `strict` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property failureMode Spring Boot integration 계약에서 `failureMode` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property defaultWaitTime Spring Boot integration 계약에서 `defaultWaitTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property defaultLeaseTime Spring Boot integration 계약에서 `defaultLeaseTime` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockNamePrefix Spring Boot integration 계약에서 `lockNamePrefix` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property metrics Spring Boot integration 계약에서 `metrics` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property spel Spring Boot integration 계약에서 `spel` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderAopProperties(
    val enabled: Boolean = true,
    val strict: Boolean = false,
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.RETHROW,
    val defaultWaitTime: Duration = DEFAULT_WAIT_TIME,
    val defaultLeaseTime: Duration = DEFAULT_LEASE_TIME,
    val lockNamePrefix: String = DEFAULT_LOCK_NAME_PREFIX,
    val metrics: Metrics = Metrics(),
    val spel: Spel = Spel(),
) : Serializable {
    /**
     * `Metrics`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property enabled Spring Boot integration 계약에서 `enabled` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     * @property tags Spring Boot integration 계약에서 `tags` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Metrics(
        val enabled: Boolean = true,
        val tags: Tags = Tags(),
    ) : Serializable {

        /**
         * Spring Boot integration 계약을 설명하는 한국어 KDoc입니다.
         */
        constructor(enabled: Boolean) : this(
            enabled = enabled,
            tags = Tags(),
        )

        /**
         * `Tags`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
         *
         * @property lockName Spring Boot integration 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property leaderId Spring Boot integration 계약에서 `leaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property backendName Spring Boot integration 계약에서 `backendName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property defaultRule Spring Boot integration 계약에서 `defaultRule` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         */
        data class Tags(
            val lockName: TagRule = TagRule(redactedValue = "redacted-lock"),
            val leaderId: TagRule = TagRule(redactedValue = "redacted-leader"),
            val backendName: TagRule = TagRule(mode = TagMode.RAW),
            val defaultRule: TagRule = TagRule(),
        ) : Serializable {
            companion object {
                private const val serialVersionUID = 1L
            }
        }

        /**
         * `TagRule`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
         *
         * @property mode Spring Boot integration 계약에서 `mode` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property allowList Spring Boot integration 계약에서 `allowList` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property denyList Spring Boot integration 계약에서 `denyList` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property hashLength Spring Boot integration 계약에서 `hashLength` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property maxLength Spring Boot integration 계약에서 `maxLength` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         * @property redactedValue Spring Boot integration 계약에서 `redactedValue` 값을 계산하거나 전달할 때 사용하는 속성입니다.
         */
        data class TagRule(
            val mode: TagMode = TagMode.REDACT,
            val allowList: Set<String> = emptySet(),
            val denyList: Set<String> = emptySet(),
            val hashLength: Int = 16,
            val maxLength: Int = 0,
            val redactedValue: String = "redacted",
        ) : Serializable {
            init {
                redactedValue.requireNotBlank("redactedValue")
                hashLength.requireInRange(1, 64, "hashLength")
                maxLength.requireZeroOrPositiveNumber("maxLength")
                if (mode == TagMode.TRUNCATE) {
                    maxLength.requirePositiveNumber("maxLength")
                }
            }

            companion object {
                private const val serialVersionUID = 1L
            }
        }

        /**
         * `TagMode`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
         *
         * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
         */
        enum class TagMode {
            REDACT,
            RAW,
            HASH,
            TRUNCATE,
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    /**
     * `Spel`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
     *
     * @property allowMethodInvocation Spring Boot integration 계약에서 `allowMethodInvocation` 값을 계산하거나 전달할 때 사용하는 속성입니다.
     */
    data class Spel(
        val allowMethodInvocation: Boolean = false,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        /**
         * `PREFIX` 값은 Spring Boot integration 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val PREFIX: String = "bluetape4k.leader.aop"

        @JvmField
        val DEFAULT_WAIT_TIME: Duration = Duration.ofSeconds(5)

        @JvmField
        val DEFAULT_LEASE_TIME: Duration = Duration.ofMinutes(1)

        /**
         * `DEFAULT_LOCK_NAME_PREFIX` 값은 Spring Boot integration 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val DEFAULT_LOCK_NAME_PREFIX: String = "\${spring.application.name:}:"
    }
}

package io.bluetape4k.leader.spring.aop.properties

import io.bluetape4k.javatimes.minutes
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * `@LeaderElection`와 `@LeaderGroupElection` advice의 공통 Spring Boot 기본 정책입니다.
 *
 * @property enabled AOP 자동 구성을 활성화할지 여부이며 기본값은 `true`입니다.
 * @property strict annotation 검증 오류를 startup failure로 승격할지 여부이며 기본값은 `false`입니다.
 * @property failureMode elector backend 오류와 lock 미획득을 처리하는 기본 정책이며 기본값은 `RETHROW`입니다.
 * @property defaultWaitTime annotation이 별도 wait time을 지정하지 않을 때 사용할 대기 기간이며 기본값은 5초입니다.
 * @property defaultLeaseTime annotation이 별도 lease time을 지정하지 않을 때 사용할 lease 기간이며 기본값은 1분입니다.
 * @property lockNamePrefix 계산된 lock 이름 앞에 붙이는 prefix이며 기본값은 `${spring.application.name:}:`입니다.
 * @property metrics AOP metric과 tag sanitization 설정이며 기본값은 `Metrics()`입니다.
 * @property spel SpEL method invocation 허용 여부이며 기본값은 비활성화된 `Spel()`입니다.
 */
@ConfigurationProperties(prefix = LeaderAopProperties.PREFIX)
data class LeaderAopProperties(
    val enabled: Boolean = true,
    val strict: Boolean = false,
    val failureMode: LeaderAspectFailureMode = LeaderAspectFailureMode.RETHROW,
    val defaultWaitTime: Duration = DEFAULT_WAIT_TIME,
    val defaultLeaseTime: Duration = DEFAULT_LEASE_TIME,
    val lockNamePrefix: String = DEFAULT_LOCK_NAME_PREFIX,
    val metrics: Metrics = Metrics(),
    val spel: Spel = Spel(),
): Serializable {
    /**
     * AOP leader 실행을 위한 metric recorder와 tag 변환 정책입니다.
     *
     * @property enabled AOP metric recorder를 활성화할지 여부이며 기본값은 `true`입니다.
     * @property tags lock, leader ID, backend tag 각각의 노출ㆍredaction 규칙이며 기본값은 `Tags()`입니다.
     */
    data class Metrics(
        val enabled: Boolean = true,
        val tags: Tags = Tags(),
    ): Serializable {

        /** `Metrics`의 legacy one-argument constructor이며 tag 정책은 기본값을 사용합니다. */
        constructor(enabled: Boolean): this(
            enabled = enabled,
            tags = Tags(),
        )

        /** Preserves Kotlin's published one-argument default-constructor descriptor. */
        @Suppress("UNUSED_PARAMETER")
        constructor(
            enabled: Boolean,
            mask: Int,
            marker: kotlin.jvm.internal.DefaultConstructorMarker?,
        ): this(
            enabled = if (mask and 0x001 != 0) true else enabled,
            tags = Tags(),
        )

        /** Preserves the one-argument data-class copy entry point from 0.4.0. */
        fun copy(enabled: Boolean): Metrics = copy(enabled = enabled, tags = tags)

        /**
         * 각 metric tag 이름에 적용할 기본 및 개별 redaction 규칙입니다.
         *
         * @property lockName lock 이름 tag 규칙이며 기본값은 `REDACT`와 `redacted-lock`입니다.
         * @property leaderId leader ID tag 규칙이며 기본값은 `REDACT`와 `redacted-leader`입니다.
         * @property backendName backend 이름 tag 규칙이며 기본값은 `RAW`입니다.
         * @property defaultRule 개별 규칙이 없을 때 적용할 규칙이며 기본값은 `TagRule()`입니다.
         */
        data class Tags(
            val lockName: TagRule = TagRule(redactedValue = "redacted-lock"),
            val leaderId: TagRule = TagRule(redactedValue = "redacted-leader"),
            val backendName: TagRule = TagRule(mode = TagMode.RAW),
            val defaultRule: TagRule = TagRule(),
        ): Serializable {
            companion object {
                private const val serialVersionUID = 1L
            }
        }

        /**
         * 하나의 metric tag 값을 raw, redacted, hashed, truncated 중 하나로 변환하는 규칙입니다.
         *
         * @property mode 적용할 변환 모드이며 기본값은 `REDACT`입니다.
         * @property allowList 값 원문을 허용할 tag/key 집합이며 기본값은 빈 집합입니다.
         * @property denyList 원문을 금지할 tag/key 집합이며 기본값은 빈 집합입니다.
         * @property hashLength `HASH` 결과의 길이이며 1..64 범위입니다. 기본값은 16입니다.
         * @property maxLength `TRUNCATE` 결과의 최대 길이이며 0 이상이어야 하고 `TRUNCATE`에서는 양수여야 합니다. 기본값은 0입니다.
         * @property redactedValue redaction 시 사용할 비공백 sentinel이며 기본값은 `redacted`입니다.
         */
        data class TagRule(
            val mode: TagMode = TagMode.REDACT,
            val allowList: Set<String> = emptySet(),
            val denyList: Set<String> = emptySet(),
            val hashLength: Int = 16,
            val maxLength: Int = 0,
            val redactedValue: String = "redacted",
        ): Serializable {
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

        /** metric tag 값을 처리하는 변환 모드입니다. */
        enum class TagMode {
            REDACT,
            RAW,
            HASH,
            TRUNCATE,
        }

        companion object {
            /** Preserves Kotlin's published one-argument `copy$default` descriptor. */
            @JvmStatic
            @Suppress("UNUSED_PARAMETER", "FunctionNaming")
            fun `copy$default`(
                self: Metrics,
                enabled: Boolean,
                mask: Int,
                marker: Any?,
            ): Metrics = self.copy(
                enabled = if (mask and 0x001 != 0) self.enabled else enabled,
            )

            private const val serialVersionUID = 1L
        }
    }

    /**
     * leader annotation의 SpEL 평가 보안 정책입니다.
     *
     * @property allowMethodInvocation SpEL이 method invocation을 수행하도록 허용할지 여부이며 기본값은 `false`입니다.
     */
    data class Spel(
        val allowMethodInvocation: Boolean = false,
    ): Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        /** `LeaderAopProperties`를 바인딩하는 Spring Boot configuration prefix입니다. */
        const val PREFIX: String = "bluetape4k.leader.aop"

        @JvmField
        val DEFAULT_WAIT_TIME: Duration = 5.seconds()

        @JvmField
        val DEFAULT_LEASE_TIME: Duration = 1.minutes()

        /** 애플리케이션 이름을 앞에 붙이는 기본 lock name prefix 표현식입니다. */
        const val DEFAULT_LOCK_NAME_PREFIX: String = $$"${spring.application.name:}:"
    }
}

package io.bluetape4k.leader.spring.aop.properties

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Leader AOP 자동 구성 속성. `bluetape4k.leader.aop.*` namespace.
 *
 * ## YAML 예
 * ```yaml
 * bluetape4k:
 *   leader:
 *     aop:
 *       enabled: true                # default true
 *       strict: false                # default false
 *       failure-mode: RETHROW        # default RETHROW
 *       default-wait-time: PT5S
 *       default-lease-time: PT1M
 *       lock-name-prefix: "myapp:"   # default "${spring.application.name}:"
 *       metrics:
 *         enabled: true              # default true — Micrometer 통합 활성화
 *       spel:
 *         allow-method-invocation: false  # default false (CVE-2022-22947 회색지대 차단)
 * ```
 *
 * @property enabled AOP 전체 활성화 (default `true`, `matchIfMissing=true`)
 * @property strict Footgun 검출 정책. `true` = startup fail, `false` = WARN
 * @property failureMode 전역 default failure mode (어노테이션 미지정 시)
 * @property defaultWaitTime 전역 default waitTime (어노테이션 미지정 시)
 * @property defaultLeaseTime 전역 default leaseTime (어노테이션 미지정 시)
 * @property lockNamePrefix [Step 3-P-Sec-2][R-34] SpEL 평가 결과 앞에 자동 prefix. empty string opt-out
 * @property metrics Micrometer metrics 활성화 옵션
 * @property spel SpEL 보안 옵션
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
) {
    /**
     * Micrometer metrics 활성화 옵션. `bluetape4k.leader.aop.metrics.*` namespace.
     *
     * @property enabled Micrometer 통합 활성화 (default `true`)
     */
    data class Metrics(
        val enabled: Boolean = true,
    )

    /**
     * SpEL 보안 옵션.
     *
     * @property allowMethodInvocation [Step 3-P-Sec-1][R-32] `withMethodResolvers()` 활성화. default `false`
     */
    data class Spel(
        val allowMethodInvocation: Boolean = false,
    )

    companion object {
        /** Default `bluetape4k.leader.aop.*` namespace prefix. */
        const val PREFIX: String = "bluetape4k.leader.aop"

        @JvmField
        val DEFAULT_WAIT_TIME: Duration = Duration.ofSeconds(5)

        @JvmField
        val DEFAULT_LEASE_TIME: Duration = Duration.ofMinutes(1)

        /** Default lock name prefix — Spring property placeholder 로 application name 자동 부착. */
        const val DEFAULT_LOCK_NAME_PREFIX: String = "\${spring.application.name:}:"
    }
}

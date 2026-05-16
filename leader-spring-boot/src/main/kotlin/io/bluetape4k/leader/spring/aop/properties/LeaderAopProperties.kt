package io.bluetape4k.leader.spring.aop.properties

import io.bluetape4k.leader.annotation.LeaderAspectFailureMode
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Leader AOP auto-configuration properties. `bluetape4k.leader.aop.*` namespace.
 *
 * ## YAML Example
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
 *         enabled: true              # default true — enables Micrometer integration
 *       spel:
 *         allow-method-invocation: false  # default false (blocks CVE-2022-22947 gray area)
 * ```
 *
 * @property enabled Enables AOP globally (default `true`, `matchIfMissing=true`)
 * @property strict Footgun detection policy. `true` = fail at startup, `false` = WARN
 * @property failureMode Global default failure mode (used when not specified on the annotation)
 * @property defaultWaitTime Global default waitTime (used when not specified on the annotation)
 * @property defaultLeaseTime Global default leaseTime (used when not specified on the annotation)
 * @property lockNamePrefix [Step 3-P-Sec-2][R-34] Prefix automatically prepended to SpEL evaluation results. Use empty string to opt out.
 * @property metrics Micrometer metrics enable option
 * @property spel SpEL security options
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
     * Micrometer metrics enable option. `bluetape4k.leader.aop.metrics.*` namespace.
     *
     * @property enabled Enables Micrometer integration (default `true`)
     */
    data class Metrics(
        val enabled: Boolean = true,
    )

    /**
     * SpEL security options.
     *
     * @property allowMethodInvocation [Step 3-P-Sec-1][R-32] Enables `withMethodResolvers()`. default `false`
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

        /** Default lock name prefix — automatically appends the application name via Spring property placeholder. */
        const val DEFAULT_LOCK_NAME_PREFIX: String = "\${spring.application.name:}:"
    }
}

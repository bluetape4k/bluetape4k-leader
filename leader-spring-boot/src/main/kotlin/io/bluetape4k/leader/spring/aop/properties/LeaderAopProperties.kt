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
) : Serializable {
    /**
     * Micrometer metrics enable option. `bluetape4k.leader.aop.metrics.*` namespace.
     *
     * @property enabled Enables Micrometer integration (default `true`)
     * @property tags Metric tag cardinality controls
     */
    data class Metrics(
        val enabled: Boolean = true,
        val tags: Tags = Tags(),
    ) : Serializable {

        /**
         * Binary-compatible constructor for callers compiled before tag controls were added.
         */
        constructor(enabled: Boolean) : this(
            enabled = enabled,
            tags = Tags(),
        )

        /**
         * Tag-key-specific cardinality controls.
         *
         * @property lockName Controls the exported `lock.name` tag
         * @property leaderId Controls the opt-in exported `leader.id` Observation tag
         * @property backendName Controls bounded backend tag values when emitted
         * @property defaultRule Controls unknown tag keys
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
         * Per-tag sanitization rule bound from Spring configuration.
         *
         * @property mode Export mode for values not handled by allow/deny lists
         * @property allowList Exact raw values allowed to pass through
         * @property denyList Exact raw values always redacted
         * @property hashLength SHA-256 hex prefix length for `HASH`
         * @property maxLength Maximum exported length for `TRUNCATE`
         * @property redactedValue Sentinel used for redacted values
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
         * Spring property enum mirroring leader-micrometer tag modes without requiring the optional module.
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
     * SpEL security options.
     *
     * @property allowMethodInvocation [Step 3-P-Sec-1][R-32] Enables `withMethodResolvers()`. default `false`
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

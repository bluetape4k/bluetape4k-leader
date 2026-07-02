package io.bluetape4k.leader.micrometer

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Cardinality-control mode for leader metric tag values.
 *
 * ## Contract
 * - [REDACT] collapses every non-allowed value to a configured sentinel.
 * - [RAW] exports the original value and should only be used for bounded, non-sensitive tags.
 * - [HASH] exports a deterministic unsalted SHA-256 hex prefix. It is pseudonymization, not secrecy.
 * - [TRUNCATE] exports a bounded prefix and requires `maxLength > 0`.
 */
enum class LeaderMetricTagMode {
    REDACT,
    RAW,
    HASH,
    TRUNCATE,
}

/**
 * Sanitization rule for one metric tag key.
 *
 * ## Contract
 * Denylist wins over allowlist. A non-empty allowlist admits exact raw values and redacts every
 * non-member. Mode-specific transformation runs only after the allow/deny decision.
 *
 * ```kotlin
 * val rule = LeaderMetricTagRule(mode = LeaderMetricTagMode.REDACT, redactedValue = "job")
 * val exported = rule.sanitize("tenant-42")
 * ```
 */
@ConsistentCopyVisibility
data class LeaderMetricTagRule private constructor(
    val mode: LeaderMetricTagMode = LeaderMetricTagMode.REDACT,
    val allowList: Set<String> = emptySet(),
    val denyList: Set<String> = emptySet(),
    val hashLength: Int = DEFAULT_HASH_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val redactedValue: String = DEFAULT_REDACTED_VALUE,
) : Serializable {

    private val allowedValues = allowList.toSet()
    private val deniedValues = denyList.toSet()

    init {
        redactedValue.requireNotBlank("redactedValue")
        hashLength.requireInRange(1, SHA256_HEX_LENGTH, "hashLength")
        maxLength.requireZeroOrPositiveNumber("maxLength")
        if (mode == LeaderMetricTagMode.TRUNCATE) {
            maxLength.requirePositiveNumber("maxLength")
        }
    }

    /**
     * Converts [rawValue] to the exported tag value according to this rule.
     */
    fun sanitize(rawValue: String): String {
        if (rawValue in deniedValues) {
            return redactedValue
        }
        if (allowedValues.isNotEmpty()) {
            return if (rawValue in allowedValues) {
                if (mode == LeaderMetricTagMode.TRUNCATE) rawValue.take(maxLength) else rawValue
            } else {
                redactedValue
            }
        }

        return when (mode) {
            LeaderMetricTagMode.REDACT -> redactedValue
            LeaderMetricTagMode.RAW -> rawValue
            LeaderMetricTagMode.HASH -> sha256Hex(rawValue).take(hashLength)
            LeaderMetricTagMode.TRUNCATE -> rawValue.take(maxLength)
        }
    }

    companion object {
        private const val serialVersionUID = 1L

        /** Default hash prefix length. */
        const val DEFAULT_HASH_LENGTH = 16

        /** Default maximum length. `0` means no truncation unless [LeaderMetricTagMode.TRUNCATE] is used. */
        const val DEFAULT_MAX_LENGTH = 0

        /** Default sentinel for unknown tag keys. */
        const val DEFAULT_REDACTED_VALUE = "redacted"

        private const val SHA256_HEX_LENGTH = 64
        private val HEX = "0123456789abcdef".toCharArray()
        private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        /** Raw passthrough rule for bounded, non-sensitive tags. */
        @JvmField
        val Raw: LeaderMetricTagRule = LeaderMetricTagRule(mode = LeaderMetricTagMode.RAW)

        /** Redaction rule for unknown or high-cardinality tag keys. */
        @JvmField
        val Redacted: LeaderMetricTagRule = LeaderMetricTagRule()

        /**
         * Factory for immutable tag sanitization rules.
         */
        operator fun invoke(
            mode: LeaderMetricTagMode = LeaderMetricTagMode.REDACT,
            allowList: Set<String> = emptySet(),
            denyList: Set<String> = emptySet(),
            hashLength: Int = DEFAULT_HASH_LENGTH,
            maxLength: Int = DEFAULT_MAX_LENGTH,
            redactedValue: String = DEFAULT_REDACTED_VALUE,
        ): LeaderMetricTagRule =
            LeaderMetricTagRule(
                mode = mode,
                allowList = allowList.toSet(),
                denyList = denyList.toSet(),
                hashLength = hashLength,
                maxLength = maxLength,
                redactedValue = redactedValue,
            )

        private fun sha256Hex(value: String): String {
            val messageDigest = SHA256.get().apply { reset() }
            val digest = messageDigest.digest(value.toByteArray(StandardCharsets.UTF_8))
            val chars = CharArray(digest.size * 2)
            digest.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                chars[index * 2] = HEX[unsigned ushr 4]
                chars[index * 2 + 1] = HEX[unsigned and 0x0f]
            }
            return String(chars)
        }
    }
}

/**
 * Tag-key-aware cardinality controls for leader Micrometer tags.
 *
 * ## Contract
 * Defaults are production-safe for high-cardinality lock names and leader IDs. Use [Raw] only after
 * accepting the time-series cardinality risk.
 */
data class LeaderMetricTagOptions(
    val lockName: LeaderMetricTagRule = LeaderMetricTagRule(redactedValue = DEFAULT_LOCK_NAME_REDACTED_VALUE),
    val leaderId: LeaderMetricTagRule = LeaderMetricTagRule(redactedValue = DEFAULT_LEADER_ID_REDACTED_VALUE),
    val backendName: LeaderMetricTagRule = LeaderMetricTagRule.Raw,
    val defaultRule: LeaderMetricTagRule = LeaderMetricTagRule.Redacted,
) : Serializable {

    /**
     * Returns the rule for [tagKey], falling back to [defaultRule] for unknown tags.
     */
    fun ruleFor(tagKey: String): LeaderMetricTagRule =
        when (tagKey) {
            MicrometerNames.TAG_LOCK_NAME -> lockName
            TAG_LEADER_ID -> leaderId
            TAG_BACKEND_NAME -> backendName
            else -> defaultRule
        }

    companion object {
        private const val serialVersionUID = 1L

        /** Tag key reserved for bounded backend identifiers. */
        const val TAG_BACKEND_NAME: String = "backend.name"

        /** Default exported value for redacted lock names. */
        const val DEFAULT_LOCK_NAME_REDACTED_VALUE: String = "redacted-lock"

        /** Default exported value for redacted leader IDs. */
        const val DEFAULT_LEADER_ID_REDACTED_VALUE: String = "redacted-leader"

        /** Production-safe default options. */
        @JvmField
        val Default: LeaderMetricTagOptions = LeaderMetricTagOptions()

        /** Raw passthrough options for legacy dashboards that accept cardinality risk. */
        @JvmField
        val Raw: LeaderMetricTagOptions = LeaderMetricTagOptions(
            lockName = LeaderMetricTagRule.Raw,
            leaderId = LeaderMetricTagRule.Raw,
            backendName = LeaderMetricTagRule.Raw,
            defaultRule = LeaderMetricTagRule.Raw,
        )
    }
}

/**
 * Maps raw leader metric tag values to exported tag values.
 *
 * ## Contract
 * Implementations must be thread-safe. The default implementation is immutable and keeps no
 * unbounded raw-value cache.
 */
fun interface LeaderMetricTagSanitizer {

    /**
     * Sanitizes [rawValue] for [tagKey].
     */
    fun sanitize(tagKey: String, rawValue: String): String

    companion object {
        /** Production-safe default sanitizer. */
        @JvmField
        val Default: LeaderMetricTagSanitizer = from(LeaderMetricTagOptions.Default)

        /** Raw passthrough sanitizer for compatibility opt-out. */
        @JvmField
        val Raw: LeaderMetricTagSanitizer = LeaderMetricTagSanitizer { _, rawValue -> rawValue }

        /**
         * Builds a sanitizer from [options].
         */
        @JvmStatic
        fun from(options: LeaderMetricTagOptions): LeaderMetricTagSanitizer {
            if (options == LeaderMetricTagOptions.Raw) {
                return Raw
            }
            return LeaderMetricTagSanitizer { tagKey, rawValue ->
                options.ruleFor(tagKey).sanitize(rawValue)
            }
        }
    }
}

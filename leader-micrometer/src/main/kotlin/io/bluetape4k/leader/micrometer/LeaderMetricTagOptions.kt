package io.bluetape4k.leader.micrometer

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * `LeaderMetricTagMode`는 Micrometer observability의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
enum class LeaderMetricTagMode {
    REDACT,
    RAW,
    HASH,
    TRUNCATE,
}

/**
 * `LeaderMetricTagRule`는 Micrometer observability에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property mode Micrometer observability 계약에서 `mode` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property allowList Micrometer observability 계약에서 `allowList` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property denyList Micrometer observability 계약에서 `denyList` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property hashLength Micrometer observability 계약에서 `hashLength` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property maxLength Micrometer observability 계약에서 `maxLength` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property redactedValue Micrometer observability 계약에서 `redactedValue` 값을 계산하거나 전달할 때 사용하는 속성입니다.
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
     * `sanitize` 호출은 Micrometer observability 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

        /**
         * `DEFAULT_HASH_LENGTH` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val DEFAULT_HASH_LENGTH = 16

        /**
         * `DEFAULT_MAX_LENGTH` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val DEFAULT_MAX_LENGTH = 0

        /**
         * `DEFAULT_REDACTED_VALUE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val DEFAULT_REDACTED_VALUE = "redacted"

        private const val SHA256_HEX_LENGTH = 64
        private val HEX = "0123456789abcdef".toCharArray()
        private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        /**
         * `Raw` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Raw: LeaderMetricTagRule = LeaderMetricTagRule(mode = LeaderMetricTagMode.RAW)

        /**
         * `Redacted` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Redacted: LeaderMetricTagRule = LeaderMetricTagRule()

        /**
         * `invoke` 호출은 Micrometer observability 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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
 * `LeaderMetricTagOptions`는 Micrometer observability에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property lockName Micrometer observability 계약에서 `lockName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaderId Micrometer observability 계약에서 `leaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property backendName Micrometer observability 계약에서 `backendName` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property defaultRule Micrometer observability 계약에서 `defaultRule` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderMetricTagOptions(
    val lockName: LeaderMetricTagRule = LeaderMetricTagRule(redactedValue = DEFAULT_LOCK_NAME_REDACTED_VALUE),
    val leaderId: LeaderMetricTagRule = LeaderMetricTagRule(redactedValue = DEFAULT_LEADER_ID_REDACTED_VALUE),
    val backendName: LeaderMetricTagRule = LeaderMetricTagRule.Raw,
    val defaultRule: LeaderMetricTagRule = LeaderMetricTagRule.Redacted,
) : Serializable {

    /**
     * `ruleFor` 호출은 Micrometer observability 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

        /**
         * `TAG_BACKEND_NAME` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val TAG_BACKEND_NAME: String = "backend.name"

        /**
         * `DEFAULT_LOCK_NAME_REDACTED_VALUE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val DEFAULT_LOCK_NAME_REDACTED_VALUE: String = "redacted-lock"

        /**
         * `DEFAULT_LEADER_ID_REDACTED_VALUE` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        const val DEFAULT_LEADER_ID_REDACTED_VALUE: String = "redacted-leader"

        /**
         * `Default` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Default: LeaderMetricTagOptions = LeaderMetricTagOptions()

        /**
         * `Raw` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
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
 * `interface` 호출은 Micrometer observability 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun interface LeaderMetricTagSanitizer {

    /**
     * `sanitize` 호출은 Micrometer observability 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun sanitize(tagKey: String, rawValue: String): String

    companion object {
        /**
         * `Default` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Default: LeaderMetricTagSanitizer = from(LeaderMetricTagOptions.Default)

        /**
         * `Raw` 값은 Micrometer observability 계약에서 사용하는 설정 또는 상태 항목입니다.
         */
        @JvmField
        val Raw: LeaderMetricTagSanitizer = LeaderMetricTagSanitizer { _, rawValue -> rawValue }

        /**
         * `from` 호출은 Micrometer observability 계약의 일부 동작을 수행합니다.
         *
         * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
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

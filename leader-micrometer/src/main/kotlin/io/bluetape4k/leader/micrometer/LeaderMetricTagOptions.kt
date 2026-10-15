package io.bluetape4k.leader.micrometer

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 민감하거나 cardinality가 높은 Micrometer tag 값을 변환하는 방식을 지정합니다.
 */
enum class LeaderMetricTagMode {
    REDACT,
    RAW,
    HASH,
    TRUNCATE,
}

/**
 * 하나의 Micrometer tag에 적용할 허용·차단 목록과 값 변환 규칙입니다.
 *
 * @property mode 허용된 raw 값을 redaction, 원문, SHA-256 prefix, 길이 제한 중 어떤 방식으로 출력할지 정합니다. 기본값은 `REDACT`입니다.
 * @property allowList 비어 있지 않으면 이 목록에 있는 값만 통과시키고 나머지는 `redactedValue`로 치환합니다. `denyList`가 먼저 적용됩니다.
 * @property denyList 이 목록에 있는 값은 허용 목록보다 먼저 `redactedValue`로 치환합니다.
 * @property hashLength `HASH` 모드에서 반환할 소문자 hexadecimal SHA-256 prefix 길이입니다. `1..64` 범위입니다.
 * @property maxLength `TRUNCATE` 모드에서 반환할 최대 문자 수입니다. 일반 모드에서는 `0`도 허용하지만 `TRUNCATE`에서는 양수여야 합니다.
 * @property redactedValue 차단되거나 허용되지 않은 값을 대신할 문자열입니다. 공백일 수 없으며 기본값은 `redacted`입니다.
 */
@ConsistentCopyVisibility
data class LeaderMetricTagRule private constructor(
    val mode: LeaderMetricTagMode = LeaderMetricTagMode.REDACT,
    val allowList: Set<String> = emptySet(),
    val denyList: Set<String> = emptySet(),
    val hashLength: Int = DEFAULT_HASH_LENGTH,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val redactedValue: String = DEFAULT_REDACTED_VALUE,
): Serializable {

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

    /** deny/allow 목록을 적용한 뒤 선택한 모드로 하나의 tag 값을 변환합니다. */
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
         * `HASH` 모드에서 사용하는 기본 SHA-256 hexadecimal prefix 길이입니다.
         */
        const val DEFAULT_HASH_LENGTH = 16

        /**
         * 기본 최대 길이입니다. `TRUNCATE`를 선택하지 않으면 사용하지 않으며 값은 `0`입니다.
         */
        const val DEFAULT_MAX_LENGTH = 0

        /**
         * 차단된 tag 값을 대체하는 기본 문자열입니다.
         */
        const val DEFAULT_REDACTED_VALUE = "redacted"

        private const val SHA256_HEX_LENGTH = 64
        private val HEX = "0123456789abcdef".toCharArray()
        private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        /**
         * tag 값을 변환하지 않고 원문으로 내보내는 규칙입니다. 민감정보가 포함되지 않은 값에만 사용하세요.
         */
        @JvmField
        val Raw: LeaderMetricTagRule = LeaderMetricTagRule(mode = LeaderMetricTagMode.RAW)

        /**
         * 모든 tag 값을 기본 redaction 문자열로 치환하는 규칙입니다.
         */
        @JvmField
        val Redacted: LeaderMetricTagRule = LeaderMetricTagRule()

        /**
         * 검증된 tag 변환 규칙을 생성합니다.
         *
         * @param mode tag 값 변환 모드입니다.
         * @param allowList 허용할 원문 값 집합입니다.
         * @param denyList 항상 redaction할 원문 값 집합입니다.
         * @param hashLength SHA-256 결과에서 사용할 hexadecimal prefix 길이입니다.
         * @param maxLength truncate 모드의 최대 길이입니다.
         * @param redactedValue 허용되지 않은 값을 대체할 문자열입니다.
         * @return 입력 집합을 복사하고 범위를 검증한 `LeaderMetricTagRule`입니다.
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
 * lock, leader ID, backend 이름별 Micrometer tag 변환 규칙을 묶습니다.
 *
 * @property lockName `lock.name` tag에 적용할 규칙입니다. 기본 redaction 대체값은 `redacted-lock`입니다.
 * @property leaderId `leader.id` tag에 적용할 규칙입니다. 기본 redaction 대체값은 `redacted-leader`입니다.
 * @property backendName `backend.name` tag에 적용할 규칙입니다. 기본값은 원문을 유지하는 `LeaderMetricTagRule.Raw`입니다.
 * @property defaultRule 위 세 종류 이외의 tag key에 적용할 규칙입니다. 기본값은 `LeaderMetricTagRule.Redacted`입니다.
 */
data class LeaderMetricTagOptions(
    val lockName: LeaderMetricTagRule = LeaderMetricTagRule(redactedValue = DEFAULT_LOCK_NAME_REDACTED_VALUE),
    val leaderId: LeaderMetricTagRule = LeaderMetricTagRule(redactedValue = DEFAULT_LEADER_ID_REDACTED_VALUE),
    val backendName: LeaderMetricTagRule = LeaderMetricTagRule.Raw,
    val defaultRule: LeaderMetricTagRule = LeaderMetricTagRule.Redacted,
): Serializable {

    /** tag key에 맞는 규칙을 반환하고, 등록되지 않은 key에는 `defaultRule`을 사용합니다. */
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
         * backend 식별자 tag의 key입니다.
         */
        const val TAG_BACKEND_NAME: String = "backend.name"

        /**
         * lock 이름을 redaction할 때 사용하는 기본 대체값입니다.
         */
        const val DEFAULT_LOCK_NAME_REDACTED_VALUE: String = "redacted-lock"

        /**
         * leader ID를 redaction할 때 사용하는 기본 대체값입니다.
         */
        const val DEFAULT_LEADER_ID_REDACTED_VALUE: String = "redacted-leader"

        /**
         * lock 이름과 leader ID는 redaction하고 backend 이름은 원문으로 유지하는 기본 tag 옵션입니다.
         */
        @JvmField
        val Default: LeaderMetricTagOptions = LeaderMetricTagOptions()

        /**
         * 모든 알려진 tag 값을 원문으로 내보내는 옵션입니다. 민감정보가 없는 환경에서만 사용하세요.
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
 * tag key와 원문 값을 받아 meter 또는 observation에 기록할 값을 반환합니다.
 */
fun interface LeaderMetricTagSanitizer {

    /** 주어진 tag key 정책으로 원문 값을 변환합니다. */
    fun sanitize(tagKey: String, rawValue: String): String

    companion object {
        /** tag key별 기본 sanitization 규칙을 사용하는 singleton입니다. */
        @JvmField
        val Default: LeaderMetricTagSanitizer = from(LeaderMetricTagOptions.Default)

        /** 모든 tag 값을 원문으로 반환하는 singleton입니다. */
        @JvmField
        val Raw: LeaderMetricTagSanitizer = LeaderMetricTagSanitizer { _, rawValue -> rawValue }

        /** tag options를 적용하는 sanitizer를 생성합니다. `Raw` options는 singleton sanitizer를 재사용합니다. */
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

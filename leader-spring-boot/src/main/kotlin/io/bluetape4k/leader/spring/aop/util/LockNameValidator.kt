package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber

/**
 * Spring AOP lock 이름에 prefix와 길이ㆍ문자 검증을 적용합니다.
 *
 * 기존 [validate]는 공개 호환 wrapper이고, 실제 backend key에는 [validateEffectiveName]이 core 정책까지 적용합니다.
 *
 * @property prefix 원래 lock 이름 앞에 붙일 문자열이며 기본값은 빈 문자열입니다.
 * @property maxLength raw 및 prefix 적용 후 이름에 허용할 최대 문자 수이며 기본값은 256입니다.
 */
class LockNameValidator(
    val prefix: String = "",
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    companion object {
        const val DEFAULT_MAX_LENGTH: Int = 256
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
    }

    init {
        maxLength.requirePositiveNumber("maxLength")
    }

    /**
     * 기존 Spring validator의 공개 동작을 보존하는 호환 wrapper입니다.
     *
     * @deprecated backend lock key에는 [validateEffectiveName]을 사용하세요. 이 메서드는
     * 기존 소비자의 256자 및 dot 허용 동작을 보존하기 위해 유지됩니다.
     */
    @Deprecated(
        message = "Use validateEffectiveName for backend lock names",
    )
    fun validate(name: String) {
        name.requireNotBlank("name")
        name.length.requireLe(maxLength, "name.length")
        name.requireMatches(NAME_PATTERN, "name") {
            "lock name contains invalid characters. Allowed: [A-Za-z0-9_:.\\-], got: '$name'"
        }
    }

    /**
     * prefix 적용 전후에 leader-core의 단일 lock-name 정책을 적용합니다.
     *
     * raw 이름과 최종 이름을 모두 검증하므로 prefix가 공통 계약을 우회하지
     * 못하며, 호출자는 backend에 전달할 최종 키를 그대로 사용할 수 있습니다.
     */
    fun validateEffectiveName(name: String): String {
        name.validateLockName("name")
        name.length.requireLe(maxLength, "name.length")
        return applyPrefix(name).also {
            it.length.requireLe(maxLength, "effectiveName.length")
            it.validateLockName()
        }
    }

    /** prefix가 비어 있으면 원문을, 아니면 prefix가 앞에 붙은 이름을 반환합니다. */
    fun applyPrefix(name: String): String =
        if (prefix.isEmpty()) name else "$prefix$name"

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("prefix", prefix)
            .add("maxLength", maxLength)
            .toString()
    }
}

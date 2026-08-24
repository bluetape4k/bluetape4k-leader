package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.leader.validateLockName
import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber

/**
 * `LockNameValidator`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 기존 [validate]는 256자와 dot을 허용하던 공개 호환 wrapper입니다. 실제 leader
 * backend에 전달할 이름은 [validateEffectiveName]으로 core의 단일 정책을 적용합니다.
 * 기존 소비자는 [validate]를 유지할 수 있지만, 새 코드는 [validateEffectiveName]으로
 * 단계적으로 이전해야 합니다.
 * @property prefix Spring Boot integration 계약에서 사용하는 속성입니다.
 * @property maxLength Spring Boot integration 계약에서 사용하는 속성입니다.
 */
class LockNameValidator(
    val prefix: String = "",
    val maxLength: Int = DEFAULT_MAX_LENGTH,
) {
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
        require(NAME_PATTERN.matches(name)) {
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
        validateLockName(name)
        name.length.requireLe(maxLength, "name.length")
        return applyPrefix(name).also {
            it.length.requireLe(maxLength, "effectiveName.length")
            validateLockName(it)
        }
    }

    /**
     * `applyPrefix` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun applyPrefix(name: String): String =
        if (prefix.isEmpty()) name else "$prefix$name"

    companion object {
        const val DEFAULT_MAX_LENGTH: Int = 256
        private val NAME_PATTERN = Regex("^[A-Za-z0-9_:.\\-]+$")
    }
}

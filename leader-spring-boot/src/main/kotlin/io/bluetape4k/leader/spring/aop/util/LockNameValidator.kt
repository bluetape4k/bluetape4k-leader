package io.bluetape4k.leader.spring.aop.util

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber

/**
 * `LockNameValidator`는 Spring Boot integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
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
     * `validate` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun validate(name: String) {
        name.requireNotBlank("name")
        name.length.requireLe(maxLength, "name.length")
        require(NAME_PATTERN.matches(name)) {
            "lock name contains invalid characters. Allowed: [A-Za-z0-9_:.\\-], got: '$name'"
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

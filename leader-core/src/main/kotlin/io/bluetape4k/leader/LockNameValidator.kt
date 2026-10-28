package io.bluetape4k.leader

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireMatches
import io.bluetape4k.support.requireNotBlank

/**
 * leader lock 이름의 공통 문자 및 길이 규칙을 검사하는 유틸리티입니다.
 */
// 첫 문자 1자(영숫자) + 이후 0~254자(영숫자/언더스코어/하이픈/콜론) = 최대 255자
private val LOCK_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-:]{0,254}$")

/**
 * 문자열이 비어 있지 않고 255자 이하이며 지정한 ASCII 문자 규칙을 만족하는지 검사합니다.
 * 첫 문자는 영문자 또는 숫자여야 하고, 나머지는 영문자·숫자·밑줄·하이픈·콜론만 허용합니다.
 * 조건을 만족하지 않으면 `IllegalArgumentException`을 던집니다.
 * @param lockName 검증할 lock 이름
 */
@Deprecated("use validateLockName extension method instead")
fun validateLockName(lockName: String) {
    lockName.requireNotBlank("lockName")
    lockName.length.requireLe(255, "lockName.length")
    lockName.requireMatches(LOCK_NAME_PATTERN, "lockName")
}

/**
 * 문자열 receiver에 공통 lock 이름 규칙을 적용하고, 통과하면 동일한 문자열을 반환합니다.
 * 이름은 비어 있을 수 없고 255자 이하여야 하며 첫 문자는 영문자 또는 숫자, 나머지는 영문자·숫자·밑줄·하이픈·콜론이어야 합니다.
 * @param parameterName 실패 메시지에 표시할 매개변수 이름
 * @return 검증을 통과한 원래 문자열
 */
fun String.validateLockName(parameterName: String = "lockName"): String = apply {
    this.requireNotBlank(parameterName = parameterName)
    this.length.requireLe(255, "$parameterName.length")
    this.requireMatches(LOCK_NAME_PATTERN, parameterName)
}

package io.bluetape4k.leader

/**
 * lockName의 공통 최소 검증.
 *
 * 허용 문자: `[a-zA-Z0-9_\-:]` (영숫자, 언더스코어, 하이픈, 콜론)
 * 첫 문자: 영숫자만 허용
 * 최대 길이: 255자
 *
 * **2-tier 설계**: 이 함수는 모든 백엔드에 공통적인 최소 규칙만 검증.
 * 백엔드 고유 규칙(예: MongoDB의 `:slot:` 금지, Lettuce의 glob 메타문자 금지)은
 * 각 백엔드 모듈의 내부 검증 함수가 이 함수 호출 후 추가로 수행.
 */
// 첫 문자 1자(영숫자) + 이후 0~254자(영숫자/언더스코어/하이픈/콜론) = 최대 255자
private val LOCK_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-:]{0,254}$")

/**
 * [lockName]이 공통 최소 규칙을 만족하는지 검증.
 * 규칙 위반 시 [IllegalArgumentException] 발생.
 */
fun validateLockName(lockName: String) {
    require(lockName.isNotBlank()) { "lockName must not be blank" }
    require(lockName.length <= 255) { "lockName must not exceed 255 characters: length=${lockName.length}" }
    require(LOCK_NAME_PATTERN.matches(lockName)) {
        "lockName contains invalid characters. Allowed: [a-zA-Z0-9_\\-:], first char must be alphanumeric, got: $lockName"
    }
}

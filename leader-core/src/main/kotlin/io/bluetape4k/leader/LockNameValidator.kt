package io.bluetape4k.leader

import io.bluetape4k.support.requireLe
import io.bluetape4k.support.requireNotBlank

/**
 * 이 선언은 leader election 계약에서 사용되는 동작과 상태를 설명합니다.
 */
// 첫 문자 1자(영숫자) + 이후 0~254자(영숫자/언더스코어/하이픈/콜론) = 최대 255자
private val LOCK_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_\\-:]{0,254}$")

/**
 * `validateLockName` 호출은 leader election 계약의 일부 동작을 수행합니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
 */
fun validateLockName(lockName: String) {
    lockName.requireNotBlank("lockName")
    lockName.length.requireLe(255, "lockName.length")
    require(LOCK_NAME_PATTERN.matches(lockName)) {
        "lockName contains invalid characters. Allowed: [a-zA-Z0-9_\\-:], first char must be alphanumeric, got: $lockName"
    }
}

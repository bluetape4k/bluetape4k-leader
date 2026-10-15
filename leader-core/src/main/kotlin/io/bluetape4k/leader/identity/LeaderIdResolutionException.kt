package io.bluetape4k.leader.identity

/**
 * leader ID 공급자에서 복구할 수 없는 오류가 발생했을 때 원인을 보존해 전달하는 예외입니다.
 *
 * @param message 오류 원인을 설명하는 메시지
 * @param cause 실패를 일으킨 원본 예외. 없을 수 있습니다.
 */
class LeaderIdResolutionException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

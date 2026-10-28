package io.bluetape4k.leader

import io.bluetape4k.exceptions.BluetapeException

/**
 * 단일 leader election 처리 중 발생한 오류를 나타내는 기본 예외입니다.
 */
open class LeaderElectionException: BluetapeException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable?): super(message, cause)
    constructor(cause: Throwable?): super(cause)
}

/**
 * 그룹 leader election 처리 중 발생한 오류를 구분하는 `LeaderElectionException` 하위 타입입니다.
 */
open class LeaderGroupElectionException: LeaderElectionException {
    constructor(): super()
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable?): super(message, cause)
    constructor(cause: Throwable?): super(cause)
}

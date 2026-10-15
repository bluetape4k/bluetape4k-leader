package io.bluetape4k.leader.spring.aop.internal

/** Aspect metadata capture 중 내부 불변식이 깨졌음을 나타내는 예외입니다. */
internal class CaptureInvariantException(message: String): IllegalStateException(message)

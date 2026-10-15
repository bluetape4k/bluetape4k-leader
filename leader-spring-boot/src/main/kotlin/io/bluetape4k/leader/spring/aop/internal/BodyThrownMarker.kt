package io.bluetape4k.leader.spring.aop.internal

import io.bluetape4k.exceptions.BluetapeException

/** Aspect 내부에서 실제 advice body 예외를 backend 예외 경로와 구분해 전달하는 marker입니다.
 *
 * @property cause advice body가 던진 원래 예외입니다.
 */
internal class BodyThrownMarker(override val cause: Throwable): BluetapeException(cause)

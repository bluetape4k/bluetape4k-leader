package io.bluetape4k.leader.spring.aop.internal

/**
 * 본문(body) 예외와 backend 예외를 구분하기 위한 마커 래퍼.
 *
 * aspect 의 outer catch block 에서 `BodyThrownMarker` 로 catch 하면 body 예외로 판별하여
 * wrapping 없이 `cause` 를 그대로 re-throw 합니다.
 *
 * `RuntimeException` 을 상속하므로 일반 catch-Throwable 에 잡히지만, 상위 catch 에서 먼저 처리합니다.
 */
internal class BodyThrownMarker(override val cause: Throwable) : RuntimeException(cause)

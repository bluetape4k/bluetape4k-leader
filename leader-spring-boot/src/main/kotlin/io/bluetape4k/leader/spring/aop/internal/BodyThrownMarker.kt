package io.bluetape4k.leader.spring.aop.internal

/**
 * Marker wrapper used to distinguish body exceptions from backend exceptions.
 *
 * When the outer catch block of the aspect catches a `BodyThrownMarker`, it treats the exception as
 * originating from the body and re-throws `cause` directly without wrapping.
 *
 * Extends `RuntimeException`, so it is caught by generic catch-Throwable blocks,
 * but the outer catch handles it first.
 */
internal class BodyThrownMarker(override val cause: Throwable) : RuntimeException(cause)

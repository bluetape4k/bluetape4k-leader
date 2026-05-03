@file:Suppress("DEPRECATION_ERROR")

package io.bluetape4k.leader.exposed.jdbc

/**
 * @deprecated 소스 호환성 typealias — 실체는 [io.bluetape4k.leader.exposed.retry.RetryStrategy]로 이동.
 * 새 코드는 `io.bluetape4k.leader.exposed.retry.RetryStrategy`를 직접 사용하세요.
 *
 * ⚠️ Kotlin typealias는 JVM class를 생성하지 않으므로 바이너리 참조는 깨질 수 있습니다.
 */
@Deprecated(
    message = "RetryStrategy moved to io.bluetape4k.leader.exposed.retry",
    replaceWith = ReplaceWith(
        "RetryStrategy",
        "io.bluetape4k.leader.exposed.retry.RetryStrategy"
    ),
    level = DeprecationLevel.HIDDEN
)
typealias RetryStrategy = io.bluetape4k.leader.exposed.retry.RetryStrategy

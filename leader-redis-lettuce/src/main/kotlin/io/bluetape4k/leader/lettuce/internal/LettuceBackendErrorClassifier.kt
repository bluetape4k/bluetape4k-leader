package io.bluetape4k.leader.lettuce.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException

/**
 * Lettuce backend exception 분류 — T7 PR 2.
 *
 * ## 동작/계약
 * - [RedisCommandTimeoutException] / [RedisConnectionException] → [BackendErrorKind.TRANSIENT] (재시도 가능)
 * - [RedisCommandExecutionException] → [BackendErrorKind.NON_TRANSIENT] (Lua 문법 오류, ACL 실패 등 — 영구 오류)
 * - 그 외 → `null` (분류 불가 — chain 다음 classifier 에 위임)
 *
 * ## 사용
 * elector 가 [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] 에 chain 으로 등록.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(
 *     LettuceBackendErrorClassifier,
 *     CoreBackendErrorClassifier,
 * )
 * ```
 */
internal object LettuceBackendErrorClassifier : BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RedisCommandTimeoutException -> BackendErrorKind.TRANSIENT
        is RedisConnectionException -> BackendErrorKind.TRANSIENT
        is RedisCommandExecutionException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}

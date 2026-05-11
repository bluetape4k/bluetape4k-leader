package io.bluetape4k.leader.redisson.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.redisson.client.RedisConnectionException
import org.redisson.client.RedisException
import org.redisson.client.RedisTimeoutException

/**
 * Redisson backend exception 분류 — T7 PR 3 (Issue #79).
 *
 * ## 동작/계약
 * - [RedisTimeoutException] / [RedisConnectionException] → [BackendErrorKind.TRANSIENT] (재시도 가능)
 * - [RedisException] (timeout/connection 이외) → [BackendErrorKind.NON_TRANSIENT] (Lua 실행 오류, ACL 실패 등)
 * - 그 외 → `null` (분류 불가 — chain 다음 classifier 에 위임)
 *
 * ## 사용
 * elector 가 [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] 에 chain 으로 등록한다.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(RedissonBackendErrorClassifier)
 * ```
 */
internal object RedissonBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is RedisTimeoutException -> BackendErrorKind.TRANSIENT
        is RedisConnectionException -> BackendErrorKind.TRANSIENT
        is RedisException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}

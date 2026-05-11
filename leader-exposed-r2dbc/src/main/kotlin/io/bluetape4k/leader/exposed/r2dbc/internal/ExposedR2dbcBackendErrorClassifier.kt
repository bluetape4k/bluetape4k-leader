package io.bluetape4k.leader.exposed.r2dbc.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import io.r2dbc.spi.R2dbcException
import io.r2dbc.spi.R2dbcNonTransientException
import io.r2dbc.spi.R2dbcRollbackException
import io.r2dbc.spi.R2dbcTimeoutException
import io.r2dbc.spi.R2dbcTransientException
import io.r2dbc.spi.R2dbcTransientResourceException

/**
 * Exposed R2DBC backend exception 분류 — T11 PR 6 (Issue #79).
 *
 * ## 동작/계약
 * - [R2dbcTimeoutException] / [R2dbcTransientException] / [R2dbcTransientResourceException]
 *   → [BackendErrorKind.TRANSIENT] (재시도 가능 — query timeout / 일시적 connection 단절)
 * - [R2dbcRollbackException] → [BackendErrorKind.NON_TRANSIENT] (rollback 후 재시도 무의미)
 * - [R2dbcNonTransientException] → [BackendErrorKind.NON_TRANSIENT] (영구 오류 — constraint violation 등)
 * - 그 외 [R2dbcException] → [BackendErrorKind.NON_TRANSIENT] (보수적 기본값)
 * - 그 외 → `null` (분류 불가 — chain 다음 classifier 에 위임)
 *
 * ## 사용
 * elector 가 [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] 에 chain 으로 등록.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)
 * ```
 *
 * ## 주의 사항
 * R2DBC SPI 는 JDBC 의 [java.sql.SQLException] 계층과 별도입니다.
 * 분류 우선순위는 좁은 타입(transient family / rollback / non-transient) 우선,
 * 일반 [R2dbcException] 은 마지막에 검사합니다.
 */
internal object ExposedR2dbcBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is R2dbcTimeoutException -> BackendErrorKind.TRANSIENT
        is R2dbcTransientResourceException -> BackendErrorKind.TRANSIENT
        is R2dbcTransientException -> BackendErrorKind.TRANSIENT
        is R2dbcRollbackException -> BackendErrorKind.NON_TRANSIENT
        is R2dbcNonTransientException -> BackendErrorKind.NON_TRANSIENT
        is R2dbcException -> BackendErrorKind.NON_TRANSIENT
        else -> null
    }
}

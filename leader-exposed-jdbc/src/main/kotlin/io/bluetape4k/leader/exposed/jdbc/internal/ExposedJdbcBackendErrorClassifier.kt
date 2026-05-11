package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * Exposed JDBC backend exception 분류 — T10 PR 5 (Issue #79).
 *
 * ## 동작/계약
 * - [SQLTransientException] / [SQLRecoverableException] → [BackendErrorKind.TRANSIENT]
 *   (재시도 가능 — 네트워크 일시 단절 / connection reset 등)
 * - [SQLNonTransientException] → [BackendErrorKind.NON_TRANSIENT]
 *   (영구 오류 — constraint violation, syntax error 등)
 * - [ExposedSQLException] / [SQLException] → SQLState 기반 분류:
 *     * `08xxx` (connection exception) → [BackendErrorKind.TRANSIENT]
 *     * `40001` (serialization failure / deadlock) → [BackendErrorKind.TRANSIENT]
 *     * 그 외 → [BackendErrorKind.NON_TRANSIENT]
 * - 그 외 → `null` (분류 불가 — chain 다음 classifier 에 위임)
 *
 * ## 사용
 * elector 가 [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] 에 chain 으로 등록.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)
 * ```
 *
 * ## 주의 사항
 * Exposed 1.2.0 은 driver 의 [SQLException] 을 [ExposedSQLException] 으로 wrap 합니다.
 * [ExposedSQLException] 은 [SQLException] 을 상속하므로 `getSQLState()` 가 cause 의 sqlState 를 그대로 노출합니다.
 */
internal object ExposedJdbcBackendErrorClassifier : BackendErrorClassifier {

    /** SQLState class 08 — connection exception (RFC: connection lost / failure). */
    private const val SQL_STATE_CONNECTION_PREFIX = "08"

    /** SQLState 40001 — serialization failure / deadlock retry. */
    private const val SQL_STATE_SERIALIZATION_FAILURE = "40001"

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is SQLTransientException -> BackendErrorKind.TRANSIENT
        is SQLRecoverableException -> BackendErrorKind.TRANSIENT
        is SQLNonTransientException -> BackendErrorKind.NON_TRANSIENT
        is SQLException -> classifyBySqlState(cause.sqlState)  // ExposedSQLException 도 SQLException 상속
        else -> null
    }

    private fun classifyBySqlState(sqlState: String?): BackendErrorKind = when {
        sqlState.isNullOrBlank() -> BackendErrorKind.NON_TRANSIENT
        sqlState.startsWith(SQL_STATE_CONNECTION_PREFIX) -> BackendErrorKind.TRANSIENT
        sqlState == SQL_STATE_SERIALIZATION_FAILURE -> BackendErrorKind.TRANSIENT
        else -> BackendErrorKind.NON_TRANSIENT
    }
}

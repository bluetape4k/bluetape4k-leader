package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * Exposed JDBC backend exception classifier — T10 PR 5 (Issue #79).
 *
 * ## Behavior / Contract
 * - [SQLTransientException] / [SQLRecoverableException] → [BackendErrorKind.TRANSIENT]
 *   (retryable — transient network disconnect / connection reset, etc.)
 * - [SQLNonTransientException] → [BackendErrorKind.NON_TRANSIENT]
 *   (permanent error — constraint violation, syntax error, etc.)
 * - [ExposedSQLException] / [SQLException] → classified by SQLState:
 *     * `08xxx` (connection exception) → [BackendErrorKind.TRANSIENT]
 *     * `40001` (serialization failure / deadlock) → [BackendErrorKind.TRANSIENT]
 *     * otherwise → [BackendErrorKind.NON_TRANSIENT]
 * - otherwise → `null` (unclassifiable — delegated to the next classifier in the chain)
 *
 * ## Usage
 * The elector registers this as a chain entry in [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier].
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(ExposedJdbcBackendErrorClassifier)
 * ```
 *
 * ## Warning
 * Exposed 1.2.0 wraps the driver's [SQLException] in [ExposedSQLException].
 * Since [ExposedSQLException] extends [SQLException], `getSQLState()` exposes the cause's sqlState as-is.
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

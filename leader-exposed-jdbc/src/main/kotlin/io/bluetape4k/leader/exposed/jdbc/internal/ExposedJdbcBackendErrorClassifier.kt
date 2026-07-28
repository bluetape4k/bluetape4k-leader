package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * `ExposedJdbcBackendErrorClassifier`는 Exposed database backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
internal object ExposedJdbcBackendErrorClassifier : BackendErrorClassifier {

    /**
     * `SQL_STATE_CONNECTION_PREFIX` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    private const val SQL_STATE_CONNECTION_PREFIX = "08"

    /**
     * `SQL_STATE_SERIALIZATION_FAILURE` 값은 Exposed database backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
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

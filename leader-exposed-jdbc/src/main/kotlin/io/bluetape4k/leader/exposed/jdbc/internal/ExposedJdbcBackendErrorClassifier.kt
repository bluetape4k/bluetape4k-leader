package io.bluetape4k.leader.exposed.jdbc.internal

import io.bluetape4k.leader.internal.BackendErrorClassifier
import io.bluetape4k.leader.internal.BackendErrorKind
import java.sql.SQLException
import java.sql.SQLNonTransientException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientException

/**
 * `ExposedJdbcBackendErrorClassifier`는 JDBC 예외 유형과 SQLState를 transient/non-transient 오류로 분류합니다.
 *
 */
internal object ExposedJdbcBackendErrorClassifier: BackendErrorClassifier {

    /** JDBC SQLState에서 연결 오류를 나타내는 `08` 접두사입니다. */
    private const val SQL_STATE_CONNECTION_PREFIX = "08"

    /** 트랜잭션 serialization failure를 나타내며 재시도 가능한 `40001` SQLState입니다. */
    private const val SQL_STATE_SERIALIZATION_FAILURE = "40001"

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is SQLTransientException   -> BackendErrorKind.TRANSIENT
        is SQLRecoverableException -> BackendErrorKind.TRANSIENT
        is SQLNonTransientException -> BackendErrorKind.NON_TRANSIENT
        is SQLException            -> classifyBySqlState(cause.sqlState)  // ExposedSQLException 도 SQLException 상속
        else                       -> null
    }

    private fun classifyBySqlState(sqlState: String?): BackendErrorKind = when {
        sqlState.isNullOrBlank()                    -> BackendErrorKind.NON_TRANSIENT
        sqlState.startsWith(SQL_STATE_CONNECTION_PREFIX) -> BackendErrorKind.TRANSIENT
        sqlState == SQL_STATE_SERIALIZATION_FAILURE -> BackendErrorKind.TRANSIENT
        else                                        -> BackendErrorKind.NON_TRANSIENT
    }
}

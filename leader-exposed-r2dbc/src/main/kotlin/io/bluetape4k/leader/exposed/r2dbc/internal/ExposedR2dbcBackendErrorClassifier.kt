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
 * `ExposedR2dbcBackendErrorClassifier`는 R2DBC 예외 subtype을 transient/non-transient 오류로 분류합니다.
 *
 */
internal object ExposedR2dbcBackendErrorClassifier: BackendErrorClassifier {

    override fun classify(cause: Throwable): BackendErrorKind? = when (cause) {
        is R2dbcTimeoutException      -> BackendErrorKind.TRANSIENT
        is R2dbcTransientResourceException -> BackendErrorKind.TRANSIENT
        is R2dbcRollbackException     -> BackendErrorKind.NON_TRANSIENT
        is R2dbcTransientException    -> BackendErrorKind.TRANSIENT
        is R2dbcNonTransientException -> BackendErrorKind.NON_TRANSIENT
        is R2dbcException             -> BackendErrorKind.NON_TRANSIENT
        else                          -> null
    }
}

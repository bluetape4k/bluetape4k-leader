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
 * Exposed R2DBC backend exception classifier — T11 PR 6 (Issue #79).
 *
 * ## Behavior / Contract
 * - [R2dbcTimeoutException] / [R2dbcTransientException] / [R2dbcTransientResourceException]
 *   → [BackendErrorKind.TRANSIENT] (retryable — query timeout / transient connection loss)
 * - [R2dbcRollbackException] → [BackendErrorKind.NON_TRANSIENT] (retry after rollback is meaningless)
 * - [R2dbcNonTransientException] → [BackendErrorKind.NON_TRANSIENT] (permanent error — constraint violation, etc.)
 * - Other [R2dbcException] → [BackendErrorKind.NON_TRANSIENT] (conservative default)
 * - Other → `null` (unclassifiable — delegated to the next classifier in the chain)
 *
 * ## Usage
 * Registered as a chain entry in [io.bluetape4k.leader.internal.CompositeBackendErrorClassifier] by the elector.
 *
 * ```kotlin
 * val classifier = CompositeBackendErrorClassifier(ExposedR2dbcBackendErrorClassifier)
 * ```
 *
 * ## Notes
 * The R2DBC SPI hierarchy is separate from JDBC's [java.sql.SQLException] hierarchy.
 * Classification priority: narrow types first (transient family / rollback / non-transient),
 * with the general [R2dbcException] checked last.
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

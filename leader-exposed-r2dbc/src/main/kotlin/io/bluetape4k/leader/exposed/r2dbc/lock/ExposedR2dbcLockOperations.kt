package io.bluetape4k.leader.exposed.r2dbc.lock

import kotlinx.coroutines.CancellationException

internal suspend fun <T> runR2dbcLockOperationPreservingCancellation(
    onFailure: (Exception) -> T,
    operation: suspend () -> T,
): T =
    try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onFailure(e)
    }

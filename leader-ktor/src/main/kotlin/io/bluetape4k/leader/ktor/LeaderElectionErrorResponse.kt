package io.bluetape4k.leader.ktor

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlin.coroutines.cancellation.CancellationException

/**
 * Optional-transport adapters use this dependency-light error response path. It deliberately
 * lives outside the StatusPages adapter so SSE/WebSocket class loading never requires the
 * optional `ktor-server-status-pages` artifact.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ApplicationCall.respondLeaderElectionErrorDirect(
    context: LeaderElectionErrorContext,
    responder: LeaderElectionErrorResponder? = null,
    exposeLockName: Boolean = false,
) {
    val config = application.attributes.getOrNull(LeaderElectionConfigKey)
    val selectedResponder = responder ?: config?.errorResponder
    val configuredOverride = config?.errorOverrides?.get(context.code)
    val callbackOverride = selectedResponder?.let { callback ->
        try {
            callback.customize(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            respondInternalErrorDirect(failure)
            return
        }
    }
    val appliedOverride = callbackOverride ?: configuredOverride
    val safeContext = appliedOverride?.let(context::withOverride) ?: context
    respondText(
        text = safeContext.toJson(
            exposeLockName = exposeLockName || appliedOverride?.exposeLockName == true,
        ),
        contentType = ContentType.Application.Json,
        status = safeContext.status,
    )
}

private suspend fun ApplicationCall.respondInternalErrorDirect(cause: Exception) {
    val context = toErrorContext(LeaderElectionErrorCode.INTERNAL, cause = cause)
    respondText(
        text = context.toJson(),
        contentType = ContentType.Application.Json,
        status = context.status,
    )
}

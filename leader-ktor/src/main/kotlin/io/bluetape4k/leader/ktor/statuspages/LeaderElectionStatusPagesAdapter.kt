package io.bluetape4k.leader.ktor.statuspages

import io.bluetape4k.leader.ktor.LeaderElectionErrorCode
import io.bluetape4k.leader.ktor.LeaderElectionErrorContext
import io.bluetape4k.leader.ktor.LeaderElectionErrorResponder
import io.bluetape4k.leader.ktor.LeaderElectionHttpException
import io.bluetape4k.leader.ktor.LeaderElectionConfigKey
import io.bluetape4k.leader.ktor.toErrorContext
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respondText
import kotlin.coroutines.cancellation.CancellationException

/**
 * Optional `ktor-server-status-pages` adapter를 명시적으로 설치합니다.
 *
 * StatusPages가 없는 애플리케이션은 [ApplicationCall.respondLeaderElectionError]를 직접
 * 호출할 수 있으며, 두 경로 모두 converter 없이 stable JSON을 반환합니다.
 */
public fun StatusPagesConfig.leaderElectionErrors(
    responder: LeaderElectionErrorResponder? = null,
) {
    exception<LeaderElectionHttpException> { call, failure ->
        call.respondLeaderElectionError(failure.context, responder)
    }
}

/** typed context를 allow-list JSON으로 직접 응답하는 dependency-light fallback입니다. */
internal suspend fun ApplicationCall.respondLeaderElectionError(
    context: LeaderElectionErrorContext,
    responder: LeaderElectionErrorResponder? = null,
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
            return respondInternalError(failure)
        }
    }
    val appliedOverride = callbackOverride ?: configuredOverride
    val safeContext = appliedOverride?.let(context::withOverride) ?: context
    respondText(
        text = safeContext.toJson(exposeLockName = appliedOverride?.exposeLockName == true),
        contentType = ContentType.Application.Json,
        status = safeContext.status,
    )
}

private suspend fun ApplicationCall.respondInternalError(cause: Exception) {
    val context = toErrorContext(LeaderElectionErrorCode.INTERNAL, cause = cause)
    respondText(
        text = context.toJson(),
        contentType = ContentType.Application.Json,
        status = context.status,
    )
}

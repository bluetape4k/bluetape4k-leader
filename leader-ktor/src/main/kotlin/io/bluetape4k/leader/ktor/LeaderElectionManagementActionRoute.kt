package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.LeaderManagementAction
import io.bluetape4k.leader.LeaderManagementActionOutcome
import io.bluetape4k.leader.LeaderManagementActionResult
import io.bluetape4k.leader.LeaderManagementHttpContract
import io.bluetape4k.leader.coroutines.SuspendLeaderManagementActionRegistry
import io.bluetape4k.leader.isManagementActionLockName
import io.bluetape4k.support.requireNotBlank
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.io.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ktor management action write route를 application auth 경계 안에 설치합니다.
 *
 * plugin은 이 route를 자동 설치하지 않습니다. 호출자는 반드시 Ktor
 * `authenticate("management")` scope 안에서 이 함수를 명시적으로 호출하고,
 * `authorize` callback으로 애플리케이션 권한을 확인해야 합니다.
 */
fun Route.leaderElectionManagementActionRoute(
    path: String? = "${LeaderElectionPluginConfig.DefaultManagementRoutePath}/actions",
    registry: SuspendLeaderManagementActionRegistry? = null,
    authorize: suspend ApplicationCall.() -> Boolean,
) {
    val routePath = normalizeManagementActionPath(
        path ?: "${LeaderElectionPluginConfig.DefaultManagementRoutePath}/actions",
    )

    post("$routePath/{lockName}") {
        val config = call.application.leaderElectionPluginConfig()
        if (!config.managementActionRouteEnabled) {
            call.respondText(
                text = "",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.NotFound,
            )
            return@post
        }
        val actionRegistry = requireNotNull(registry ?: config.managementActionRegistry) {
            "managementActionRouteEnabled=true 이면 application-owned managementActionRegistry를 설정해야 합니다."
        }
        val authorized = try {
            authorize(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            call.respondManagementActionError(LeaderManagementRouteError.authorizationFailed())
            return@post
        }

        if (!authorized) {
            call.respondManagementActionError(LeaderManagementRouteError.authorizationDenied())
            return@post
        }

        val lockName = call.parameters[LOCK_NAME_PARAMETER].orEmpty()
        if ('/' in lockName) {
            call.respondText(
                text = "",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.NotFound,
            )
            return@post
        }
        val result = if (isManagementActionLockName(lockName)) {
            actionRegistry.release(lockName)
        } else {
            invalidLockNameResult()
        }
        call.respondText(
            text = result.toJson(),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.fromValue(LeaderManagementHttpContract.statusCode(result.outcome)),
        )
    }
}

/** 관리 action path가 slash 중복 없이 canonical route가 되도록 정규화합니다. */
internal fun normalizeManagementActionPath(path: String): String {
    val trimmed = path.trim()
    trimmed.requireNotBlank("managementActionRoutePath")
    val absolute = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
    return absolute.trimEnd('/').ifEmpty { "/" }
}

/** plugin 설정의 management route를 기준으로 action route 기본 경로를 계산합니다. */
internal fun LeaderElectionPluginConfig.managementActionPath(): String =
    normalizeManagementActionPath(managementActionRoutePath ?: "${managementRoutePath.trimEnd('/')}/actions")

/** 인증 callback 실패나 거부 시 고정된 allow-list body만 반환합니다. */
data class LeaderManagementRouteError(
    val code: String,
    val message: String,
) : Serializable {

    init {
        require(code in ALLOWED_CODES) { "알 수 없는 management route error code입니다." }
    }

    internal fun toJson(): String =
        buildString {
            append("{\"code\":").append(code.jsonValue())
            append(",\"message\":").append(message.jsonValue())
            append('}')
        }

    companion object {
        private val ALLOWED_CODES = setOf("AUTHORIZATION_DENIED", "AUTHORIZATION_FAILED")
        private const val serialVersionUID = 1L

        fun authorizationDenied(): LeaderManagementRouteError =
            LeaderManagementRouteError("AUTHORIZATION_DENIED", "management action authorization denied")

        fun authorizationFailed(): LeaderManagementRouteError =
            LeaderManagementRouteError("AUTHORIZATION_FAILED", "management action authorization failed")
    }
}

private suspend fun ApplicationCall.respondManagementActionError(error: LeaderManagementRouteError) {
    respondText(
        text = error.toJson(),
        contentType = ContentType.Application.Json,
        status = when (error.code) {
            "AUTHORIZATION_DENIED" -> HttpStatusCode.Forbidden
            else -> HttpStatusCode.InternalServerError
        },
    )
}

private fun invalidLockNameResult(): LeaderManagementActionResult =
    LeaderManagementActionResult(
        action = LeaderManagementAction.RELEASE,
        outcome = LeaderManagementActionOutcome.INVALID_LOCK_NAME,
        mutationAttempted = false,
    )

private fun LeaderManagementActionResult.toJson(): String =
    buildString {
        append("{\"action\":").append(action.name.jsonValue())
        append(",\"outcome\":").append(outcome.name.jsonValue())
        append(",\"mutationAttempted\":").append(mutationAttempted)
        append('}')
    }

private const val LOCK_NAME_PARAMETER = "lockName"

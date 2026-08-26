package io.bluetape4k.leader.ktor

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import io.ktor.http.HttpStatusCode
import java.io.Serializable
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ktor leader-election error의 machine-readable code입니다.
 *
 * 정상 lock contention은 이 오류로 변환하지 않고 기존 `null`/skip 계약을 유지합니다.
 */
enum class LeaderElectionErrorCode {
    INVALID_LOCK_NAME,
    NOT_LEADER,
    LEADER_LOCKED,
    BACKEND_UNAVAILABLE,
    CONFIGURATION,
    INTERNAL,
    INVALID_CURSOR,
}

/**
 * Ktor 오류 응답에 필요한 안정적인 공개 context입니다.
 *
 * 원래 `Throwable`과 backend 상세는 보관하지 않으며, `lockName`은 명시적으로 노출할 때만
 * 응답에 포함됩니다.
 */
data class LeaderElectionErrorContext(
    val code: LeaderElectionErrorCode,
    val message: String,
    val status: HttpStatusCode,
    val lockName: String? = null,
) : Serializable {

    init {
        require(status in LEADER_ELECTION_ERROR_STATUSES) {
            "오류 응답 status는 allow-list에 있어야 합니다: $status"
        }
    }

    /** allow-list 필드만 stable 순서로 직렬화합니다. */
    fun toJson(exposeLockName: Boolean = false): String =
        buildStableJson(this, exposeLockName)

    /** typed override가 허용한 status와 lockName 노출만 적용합니다. */
    fun withOverride(override: LeaderElectionErrorOverride): LeaderElectionErrorContext =
        copy(
            status = override.status ?: status,
            lockName = if (override.exposeLockName) lockName else null,
        )

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 애플리케이션이 오류 status와 lockName 노출 여부를 제한적으로 조정하는 typed policy입니다.
 */
data class LeaderElectionErrorOverride(
    val status: HttpStatusCode? = null,
    val exposeLockName: Boolean = false,
) : Serializable {

    init {
        require(status == null || status in LEADER_ELECTION_ERROR_STATUSES) {
            "오류 override status는 allow-list에 있어야 합니다: $status"
        }
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** 내부 adapter가 typed error context를 StatusPages 예외로 전달하는 예외입니다. */
internal class LeaderElectionHttpException(
    val context: LeaderElectionErrorContext,
    cause: Throwable? = null,
) : RuntimeException(context.message, cause)

/**
 * 오류 응답을 typed override로만 사용자화하는 callback입니다.
 *
 * 응답 body나 `ApplicationCall`을 직접 조작할 수 없으므로 stable allow-list 경계를
 * 우회하지 않습니다.
 */
fun interface LeaderElectionErrorResponder {
    fun customize(context: LeaderElectionErrorContext): LeaderElectionErrorOverride
}

internal val LEADER_ELECTION_ERROR_STATUSES: Set<HttpStatusCode> = setOf(
    HttpStatusCode.BadRequest,
    HttpStatusCode.Locked,
    HttpStatusCode.ServiceUnavailable,
    HttpStatusCode.InternalServerError,
)

internal fun toErrorContext(
    code: LeaderElectionErrorCode,
    lockName: String? = null,
    cause: Throwable? = null,
): LeaderElectionErrorContext {
    if (cause is CancellationException) throw cause
    cause?.let { throwable ->
        LeaderElectionErrorLogger.log.warn {
            "leader election error mapped — code=${code.name}, " +
                "causeType=${throwable::class.simpleName ?: "Unknown"}"
        }
    }
    return LeaderElectionErrorContext(
        code = code,
        message = code.defaultMessage,
        status = code.defaultStatus,
        lockName = lockName,
    )
}

private fun buildStableJson(
    context: LeaderElectionErrorContext,
    exposeLockName: Boolean,
): String = buildString {
    append("{\"code\":").append(context.code.name.jsonValue())
    append(",\"message\":").append(context.message.jsonValue())
    append(",\"status\":").append(context.status.value)
    if (exposeLockName && context.lockName != null) {
        append(",\"lockName\":").append(context.lockName.jsonValue())
    }
    append('}')
}

private val LeaderElectionErrorCode.defaultStatus: HttpStatusCode
    get() = when (this) {
        LeaderElectionErrorCode.INVALID_LOCK_NAME,
        LeaderElectionErrorCode.INVALID_CURSOR,
        -> HttpStatusCode.BadRequest

        LeaderElectionErrorCode.LEADER_LOCKED -> HttpStatusCode.Locked
        LeaderElectionErrorCode.NOT_LEADER,
        LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
        -> HttpStatusCode.ServiceUnavailable

        LeaderElectionErrorCode.CONFIGURATION,
        LeaderElectionErrorCode.INTERNAL,
        -> HttpStatusCode.InternalServerError
    }

private val LeaderElectionErrorCode.defaultMessage: String
    get() = when (this) {
        LeaderElectionErrorCode.INVALID_LOCK_NAME -> "lock name is invalid"
        LeaderElectionErrorCode.NOT_LEADER -> "leader state does not allow this request"
        LeaderElectionErrorCode.LEADER_LOCKED -> "leader lock is already held"
        LeaderElectionErrorCode.BACKEND_UNAVAILABLE -> "leader backend is temporarily unavailable"
        LeaderElectionErrorCode.CONFIGURATION -> "leader election configuration is invalid"
        LeaderElectionErrorCode.INTERNAL -> "leader election request failed"
        LeaderElectionErrorCode.INVALID_CURSOR -> "cursor is invalid"
    }

private object LeaderElectionErrorLogger : KLogging()

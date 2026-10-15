package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.ktor.statuspages.respondLeaderElectionError
import io.bluetape4k.leader.validateLockName
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.coroutines.cancellation.CancellationException

/**
 * management route가 조회할 유효한 lock 이름을 중복 없이 정렬하여 보관합니다.
 */
class LeaderElectionManagementRegistry(
    initialLockNames: Iterable<String> = emptyList(),
) {
    private val lockNames = ConcurrentSkipListSet<String>()

    init {
        initialLockNames.forEach(::register)
    }

    /** lock 이름을 검증한 뒤 registry에 추가합니다. 이미 등록된 이름은 한 번만 보관됩니다. */
    fun register(lockName: String) {
        lockName.validateLockName()
        lockNames.add(lockName)
    }

    /** 현재 등록된 lock 이름을 정렬된 불변 snapshot으로 반환합니다. */
    fun snapshot(): List<String> =
        lockNames.toList()
}

/**
 * 등록된 lock의 leader 상태를 JSON으로 반환하는 read-only management route를 설치합니다.
 */
fun Application.leaderElectionManagementRoute(
    path: String = LeaderElectionPluginConfig.DefaultManagementRoutePath,
    leaderElection: SuspendLeaderElector = resolveLeaderElection(),
    registry: LeaderElectionManagementRegistry = leaderElectionPluginConfig().managementRegistry,
) {
    val routePath = normalizeLeaderRoutePath(path)

    routing {
        get(routePath) {
            try {
                call.respondText(
                    text = registry.toJson(leaderElection),
                    contentType = ContentType.Application.Json,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LeaderElectionHttpException) {
                call.respondLeaderElectionError(failure.context)
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun LeaderElectionManagementRegistry.toJson(
    leaderElection: SuspendLeaderElector,
): String = buildString {
    append("{\"locks\":[")
    snapshot().forEachIndexed { index, lockName ->
        if (index > 0) append(',')
        try {
            lockName.validateLockName()
        } catch (failure: IllegalArgumentException) {
            throw LeaderElectionHttpException(
                context = toErrorContext(
                    code = LeaderElectionErrorCode.INVALID_LOCK_NAME,
                    cause = failure,
                ),
                cause = failure,
            )
        }
        val state = try {
            leaderElection.state(lockName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw LeaderElectionHttpException(
                context = toErrorContext(
                    code = LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
                    cause = failure,
                ),
                cause = failure,
            )
        }
        append('{')
        append("\"name\":\"").append(lockName.jsonEscape()).append("\",")
        append("\"status\":\"").append(state.status.name).append("\",")
        append("\"leaderId\":").append(state.leader?.auditLeaderId?.jsonValue() ?: "null").append(',')
        append("\"leaseExpiry\":").append(state.leader?.leaseUntil?.toString()?.jsonValue() ?: "null")
        append('}')
    }
    append("]}")
}

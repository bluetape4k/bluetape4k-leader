package io.bluetape4k.leader.ktor

import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.support.requireNotBlank
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentSkipListSet

/**
 * `LeaderElectionManagementRegistry`는 Ktor integration의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
class LeaderElectionManagementRegistry(
    initialLockNames: Iterable<String> = emptyList(),
) {
    private val lockNames = ConcurrentSkipListSet<String>()

    init {
        initialLockNames.forEach(::register)
    }

    /**
     * `register` 호출은 Ktor integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun register(lockName: String) {
        lockName.requireNotBlank("lockName")
        lockNames.add(lockName)
    }

    /**
     * `snapshot` 호출은 Ktor integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun snapshot(): List<String> =
        lockNames.toList()
}

/**
 * `Application` 호출은 Ktor integration 계약의 일부 동작을 수행합니다.
 *
 * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
 */
fun Application.leaderElectionManagementRoute(
    path: String = LeaderElectionPluginConfig.DefaultManagementRoutePath,
    leaderElection: SuspendLeaderElector = resolveLeaderElection(),
    registry: LeaderElectionManagementRegistry = leaderElectionPluginConfig().managementRegistry,
) {
    path.requireNotBlank("path")
    val routePath = if (path.startsWith("/")) path else "/$path"

    routing {
        get(routePath) {
            call.respondText(
                text = registry.toJson(leaderElection),
                contentType = ContentType.Application.Json,
            )
        }
    }
}

private fun LeaderElectionManagementRegistry.toJson(leaderElection: SuspendLeaderElector): String =
    buildString {
        append("{\"locks\":[")
        snapshot().forEachIndexed { index, lockName ->
            if (index > 0) append(',')
            val state = leaderElection.state(lockName)
            append('{')
            append("\"name\":\"").append(lockName.jsonEscape()).append("\",")
            append("\"status\":\"").append(state.status.name).append("\",")
            append("\"leaderId\":").append(state.leader?.auditLeaderId?.jsonValue() ?: "null").append(',')
            append("\"leaseExpiry\":").append(state.leader?.leaseUntil?.toString()?.jsonValue() ?: "null")
            append('}')
        }
        append("]}")
    }

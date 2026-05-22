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
 * JVM-local registry of lock names exposed through the Ktor management route.
 *
 * ## Behavior / Contract
 * - Thread-safe: backed by [ConcurrentSkipListSet]; safe to call [register] concurrently.
 * - [snapshot] returns a stable sorted copy for deterministic JSON responses.
 * - The registry is JVM-local. It does not discover lock names from the backend.
 * - Seed static lock names at startup, or register lock names on the fly as application code starts
 *   scheduled leader-only work.
 *
 * ```kotlin
 * val registry = LeaderElectionManagementRegistry(listOf("batch-job", "nightly-sync"))
 * registry.register("on-demand-lock")
 * ```
 */
class LeaderElectionManagementRegistry(
    initialLockNames: Iterable<String> = emptyList(),
) {
    private val lockNames = ConcurrentSkipListSet<String>()

    init {
        initialLockNames.forEach(::register)
    }

    /**
     * Registers [lockName] as known to the management route.
     */
    fun register(lockName: String) {
        lockName.requireNotBlank("lockName")
        lockNames.add(lockName)
    }

    /**
     * Returns a stable sorted snapshot of known lock names.
     */
    fun snapshot(): List<String> =
        lockNames.toList()
}

/**
 * Installs `GET /management/leaderElection`-style JSON status route.
 *
 * ## Behavior / Contract
 * - [registry] provides the lock names to inspect.
 * - [leaderElection] is queried with `state(lockName)` at request time.
 * - The JSON is emitted as text so consumers do not need to install a serialization plugin.
 * - This route is installed on the application's main routing pipeline. Protect it with an
 *   authentication plugin, network policy, or a dedicated internal port before exposing it outside
 *   a trusted management boundary.
 *
 * Requires [LeaderElectionPlugin] to be installed first when [leaderElection] or [registry] are not
 * passed explicitly; otherwise the default argument resolution throws [IllegalStateException].
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

private fun String.jsonValue(): String =
    "\"${jsonEscape()}\""

private fun String.jsonEscape(): String =
    buildString(length) {
        this@jsonEscape.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

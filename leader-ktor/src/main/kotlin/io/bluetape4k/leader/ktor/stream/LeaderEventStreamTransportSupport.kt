@file:Suppress("MatchingDeclarationName", "ReturnCount", "TooGenericExceptionCaught")

package io.bluetape4k.leader.ktor.stream

import io.bluetape4k.leader.ktor.LeaderElectionErrorCode
import io.bluetape4k.leader.ktor.respondLeaderElectionErrorDirect
import io.bluetape4k.leader.ktor.toErrorContext
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.CancellationException

/** Route-scoped preflight configuration shared by the optional transports. */
internal class LeaderEventStreamPreflightConfig {
    lateinit var hub: LeaderEventStreamHub
    lateinit var config: LeaderEventStreamConfig
    lateinit var connectionKey: AttributeKey<LeaderEventStreamHub.LeaderEventStreamConnection>
    var acceptsLastEventId: Boolean = false
}

/**
 * Authentication-aware preflight plugin. The Call-phase hook runs after parent route
 * validators (including authentication/authorization), so handled 401/403 calls never reach
 * connection admission. It also keeps the adapter usable on an explicitly unprotected route
 * while preserving the same bounded validation path.
 */
internal fun Route.installLeaderEventStreamPreflight(
    hub: LeaderEventStreamHub,
    config: LeaderEventStreamConfig,
    connectionKey: AttributeKey<LeaderEventStreamHub.LeaderEventStreamConnection>,
    acceptsLastEventId: Boolean,
): Route {
    install(LeaderEventStreamPreflightPlugin) {
        this.hub = hub
        this.config = config
        this.connectionKey = connectionKey
        this.acceptsLastEventId = acceptsLastEventId
    }
    return this
}

private object LeaderEventStreamCallHook : Hook<suspend (PipelineContext<Unit, PipelineCall>) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (PipelineContext<Unit, PipelineCall>) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Call) {
            handler(this)
        }
    }
}

private val LeaderEventStreamPreflightPlugin = createRouteScopedPlugin(
    name = "LeaderEventStreamPreflight",
    createConfiguration = ::LeaderEventStreamPreflightConfig,
) {
    val config = pluginConfig

    suspend fun preflight(call: ApplicationCall): Boolean {
        if (call.attributes.getOrNull(config.connectionKey) != null) return true
        return prepareLeaderEventStreamConnection(call, config)
    }

    on(LeaderEventStreamCallHook) { context ->
        val call = context.call
        if (call.isHandled) return@on
        if (preflight(call)) context.proceed()
    }
}

private suspend fun prepareLeaderEventStreamConnection(
    call: ApplicationCall,
    config: LeaderEventStreamPreflightConfig,
): Boolean {
    val lockResult = parseLockName(call, config.config.eventStreamAllLocksEnabled)
    if (lockResult.isFailure) {
        return call.respondLeaderEventStreamError(LeaderElectionErrorCode.INVALID_LOCK_NAME)
    }
    val lockName = lockResult.getOrNull()

    val cursorResult = parseCursor(call, config.acceptsLastEventId)
    if (cursorResult.isFailure) {
        return call.respondLeaderEventStreamError(LeaderElectionErrorCode.INVALID_CURSOR)
    }
    val cursor = cursorResult.getOrNull()

    val connection = try {
        config.hub.acquireConnection(lockName = lockName, afterSequence = cursor)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: LeaderEventStreamConnectionLimitException) {
        return call.respondLeaderEventStreamError(LeaderElectionErrorCode.BACKEND_UNAVAILABLE)
    } catch (_: LeaderEventStreamClosedException) {
        return call.respondLeaderEventStreamError(LeaderElectionErrorCode.BACKEND_UNAVAILABLE)
    } catch (_: IllegalArgumentException) {
        return call.respondLeaderEventStreamError(LeaderElectionErrorCode.INVALID_LOCK_NAME)
    } catch (_: Exception) {
        return call.respondLeaderEventStreamError(LeaderElectionErrorCode.INTERNAL)
    }
    call.attributes.put(config.connectionKey, connection)
    return true
}

private fun parseLockName(call: ApplicationCall, allLocksEnabled: Boolean): Result<String?> {
    val values = call.request.queryParameters.getAll("lockName").orEmpty()
    if (values.size > 1) return Result.failure(IllegalArgumentException("lockName must be singular"))
    val value = values.singleOrNull()
    if (value == null) {
        return if (allLocksEnabled) Result.success(null)
        else Result.failure(IllegalArgumentException("lockName is required"))
    }
    return runCatching {
        io.bluetape4k.leader.validateLockName(value)
        value
    }
}

private fun parseCursor(call: ApplicationCall, acceptsLastEventId: Boolean): Result<Long?> {
    val queryValues = call.request.queryParameters.getAll("afterSequence").orEmpty()
    if (queryValues.size > 1) return Result.failure(IllegalArgumentException("afterSequence must be singular"))
    val query = queryValues.singleOrNull()?.trim().orEmpty().ifEmpty { null }

    val headerValues = if (acceptsLastEventId) {
        call.request.headers.getAll("Last-Event-ID").orEmpty()
    } else {
        emptyList()
    }
    if (headerValues.size > 1) return Result.failure(IllegalArgumentException("Last-Event-ID must be singular"))
    val header = headerValues.singleOrNull()?.trim().orEmpty().ifEmpty { null }
    if (query != null && header != null) {
        return Result.failure(IllegalArgumentException("cursor must be supplied once"))
    }
    return runCatching { parseLeaderEventStreamCursor(query ?: header) }
}

private suspend fun ApplicationCall.respondLeaderEventStreamError(
    code: LeaderElectionErrorCode,
): Boolean {
    respondLeaderElectionErrorDirect(toErrorContext(code))
    return false
}

package io.bluetape4k.leader.ktor

import io.bluetape4k.logging.warn
import io.bluetape4k.leader.LeaderState
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirer
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseAcquirerSupport
import io.bluetape4k.leader.coroutines.SuspendLeaderLeaseHandle
import io.bluetape4k.leader.validateLockName
import io.bluetape4k.leader.ktor.statuspages.respondLeaderElectionError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.call
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.application.isHandled
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 요청 route가 어떤 leader authority를 사용할지 지정합니다. */
enum class LeaderRouteAuthorityMode {
    /** 현재 leader 상태를 한 번 읽는 passive 확인입니다. */
    STATE,

    /** 요청 처리 전체를 bounded suspend lease로 감쌉니다. */
    LEASE,
}

/** `leaderGuard` route-scoped plugin의 공개 설정입니다. */
class LeaderRouteGuardConfig {
    /** 기본값은 passive state 확인입니다. */
    var authorityMode: LeaderRouteAuthorityMode = LeaderRouteAuthorityMode.STATE

    /** STATE 거부에 사용할 status입니다. KTOR-02 allow-list만 허용합니다. */
    var rejectionStatus: HttpStatusCode = HttpStatusCode.ServiceUnavailable

    /** 오류 JSON에 lock metadata를 포함할지 지정합니다. 기본값은 비노출입니다. */
    var exposeMetadata: Boolean = false

    /** LEASE acquire와 release 각각에 사용할 bounded request 제한 시간입니다. */
    var leaseMaxDuration: Duration = 30.seconds

    /** 지정하면 기본 elector 대신 이 함수로 상태를 조회합니다. */
    var stateProvider: ((String) -> LeaderState)? = null

    /** 지정하면 기본 elector capability 대신 이 acquirer를 사용합니다. */
    var leaseAcquirer: SuspendLeaderLeaseAcquirer? = null

    /** 오류 status/lockName 노출을 typed policy로 제한적으로 조정합니다. */
    var errorResponder: LeaderElectionErrorResponder? = null

    internal var lockName: String = ""
}

/**
 * 현재 route 아래에 passive leader guard를 설치합니다.
 *
 * 인증/인가와 같은 상위 route plugin이 먼저 실행되며, 인증이 완료된 뒤에만
 * leader state 또는 lease capability를 확인합니다.
 */
fun Route.leaderGuard(
    lockName: String,
    configure: LeaderRouteGuardConfig.() -> Unit = {},
    build: Route.() -> Unit = {},
): Route {
    validateLockName(lockName)
    val configured = LeaderRouteGuardConfig().apply(configure).also { it.lockName = lockName }
    val guardedRoute = createChild(LeaderGuardRouteSelector(routeGuardIds.incrementAndGet()))
    guardedRoute.install(LeaderRouteGuardPlugin) {
        authorityMode = configured.authorityMode
        rejectionStatus = configured.rejectionStatus
        exposeMetadata = configured.exposeMetadata
        leaseMaxDuration = configured.leaseMaxDuration
        stateProvider = configured.stateProvider
        leaseAcquirer = configured.leaseAcquirer
        errorResponder = configured.errorResponder
        this.lockName = configured.lockName
    }
    guardedRoute.apply(build)
    return guardedRoute
}

/** `leaderGuard`의 기본 STATE mode를 명시적으로 사용하는 짧은 DSL입니다. */
fun Route.leaderOnlyRoute(
    lockName: String,
    build: Route.() -> Unit = {},
): Route = leaderGuard(lockName = lockName, build = build)

private val routeGuardIds = AtomicLong()

private class LeaderGuardRouteSelector(
    private val id: Long,
) : RouteSelector() {
    override suspend fun evaluate(
        context: RoutingResolveContext,
        segmentIndex: Int,
    ): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "<leader-guard-$id>"
}

private object LeaderGuardCallHook : Hook<suspend (PipelineContext<Unit, PipelineCall>) -> Unit> {
    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend (PipelineContext<Unit, PipelineCall>) -> Unit,
    ) {
        pipeline.intercept(ApplicationCallPipeline.Call) {
            handler(this)
        }
    }
}

private val LeaderRouteGuardPlugin = createRouteScopedPlugin(
    name = "LeaderRouteGuard",
    createConfiguration = ::LeaderRouteGuardConfig,
) {
    val config = pluginConfig
    val resolvedElector = application.attributes.getOrNull(LeaderElectionConfigKey)?.leaderElection

    validateGuardConfiguration(config, resolvedElector)

    on(AuthenticationChecked) { call ->
        if (call.isHandled) return@on

        when (config.authorityMode) {
            LeaderRouteAuthorityMode.STATE -> checkState(call, config, resolvedElector)
            LeaderRouteAuthorityMode.LEASE -> acquireLease(call, config, resolvedElector)
        }
    }

    on(LeaderGuardCallHook) { context ->
        val call = context.call
        if (call.isHandled) return@on

        val lease: SuspendLeaderLeaseHandle? = call.attributes.getOrNull(LeaderLeaseHandleKey)
        if (lease == null) {
            context.proceed()
            return@on
        }

        var downstreamFailure: Throwable? = null
        try {
            context.proceed()
        } catch (failure: Throwable) {
            downstreamFailure = failure
            throw failure
        } finally {
            releaseLease(lease, config.leaseMaxDuration, downstreamFailure)
        }
    }
}

private val LeaderLeaseHandleKey = io.ktor.util.AttributeKey<SuspendLeaderLeaseHandle>(
    "io.bluetape4k.leader.ktor.LeaderRouteGuardLeaseHandle",
)

private fun validateGuardConfiguration(
    config: LeaderRouteGuardConfig,
    elector: SuspendLeaderElector?,
) {
    require(config.rejectionStatus in LEADER_ELECTION_ERROR_STATUSES) {
        "rejectionStatus는 leader-election 오류 allow-list에 있어야 합니다: ${config.rejectionStatus}"
    }
    if (config.authorityMode == LeaderRouteAuthorityMode.LEASE) {
        require(config.leaseMaxDuration.isFinite() && config.leaseMaxDuration.isPositive()) {
            "leaseMaxDuration은 양수이면서 유한해야 합니다: ${config.leaseMaxDuration}"
        }
        val explicitAcquirer = config.leaseAcquirer
        if (explicitAcquirer == null) {
            val support = elector as? SuspendLeaderLeaseAcquirerSupport
            require(support == null || support.leaseCapabilityAvailable) {
                "LEASE mode의 elector가 suspend lease capability를 제공하지 않습니다."
            }
            require(elector is SuspendLeaderLeaseAcquirer) {
                "LEASE mode에는 SuspendLeaderLeaseAcquirer capability가 필요합니다."
            }
        }
    } else if (config.stateProvider == null) {
        require(elector != null) {
            "STATE mode에는 LeaderElectionPlugin의 leaderElection 또는 explicit stateProvider가 필요합니다."
        }
        require(elector.supportsAuditLeaderState) {
            "STATE mode에는 audit leader state를 제공하는 elector가 필요합니다."
        }
    }
}

private suspend fun checkState(
    call: ApplicationCall,
    config: LeaderRouteGuardConfig,
    elector: SuspendLeaderElector?,
) {
    val state = try {
        config.stateProvider?.invoke(config.lockName) ?: checkNotNull(elector).state(config.lockName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        call.respondGuardError(
            code = LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
            config = config,
            cause = failure,
        )
        return
    }

    if (!state.isOccupied) {
        call.respondGuardError(
            code = LeaderElectionErrorCode.NOT_LEADER,
            config = config,
            status = config.rejectionStatus,
        )
    }
}

private suspend fun acquireLease(
    call: ApplicationCall,
    config: LeaderRouteGuardConfig,
    elector: SuspendLeaderElector?,
) {
    val acquirer = config.leaseAcquirer ?: (elector as SuspendLeaderLeaseAcquirer)
    val lease = try {
        withTimeoutOrNull(config.leaseMaxDuration) {
            acquirer.tryAcquire(config.lockName)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        call.respondGuardError(
            code = LeaderElectionErrorCode.BACKEND_UNAVAILABLE,
            config = config,
            cause = failure,
        )
        return
    }

    if (lease == null) {
        call.respondGuardError(
            code = LeaderElectionErrorCode.LEADER_LOCKED,
            config = config,
        )
    } else {
        call.attributes.put(LeaderLeaseHandleKey, lease)
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun releaseLease(
    lease: SuspendLeaderLeaseHandle,
    timeout: Duration,
    downstreamFailure: Throwable?,
) {
    try {
        withContext(NonCancellable) {
            withTimeoutOrNull(timeout) {
                lease.release()
            }
        }
    } catch (failure: Throwable) {
        LeaderElectionPluginInternals.log.warn {
            "leader route lease release failed — lockName=${lease.lockName}, " +
                "causeType=${failure::class.simpleName ?: "Unknown"}, " +
                "downstreamFailure=${downstreamFailure?.javaClass?.simpleName ?: "none"}"
        }
    }
}

private suspend fun ApplicationCall.respondGuardError(
    code: LeaderElectionErrorCode,
    config: LeaderRouteGuardConfig,
    status: HttpStatusCode? = null,
    cause: Throwable? = null,
) {
    val context = toErrorContext(
        code = code,
        lockName = config.lockName.takeIf { config.exposeMetadata },
        cause = cause,
    )
    val adjusted = status?.let { context.copy(status = it) } ?: context
    respondLeaderElectionError(
        context = adjusted,
        responder = config.errorResponder,
        exposeLockName = config.exposeMetadata,
    )
}

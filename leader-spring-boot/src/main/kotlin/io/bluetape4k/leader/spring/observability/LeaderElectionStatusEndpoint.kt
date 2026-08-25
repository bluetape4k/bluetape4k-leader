package io.bluetape4k.leader.spring.observability

import io.bluetape4k.support.requireNotNull
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.jvm.internal.DefaultConstructorMarker
import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderElector

/**
 * `LeaderElectionStatusEndpoint`는 Spring Boot integration의 leader election,
 * route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 * @property selectedState 관측에 사용할 backend와 상태 제공자입니다.
 * @property registry Spring Boot integration 계약에서 사용하는 속성입니다.
 */
@Endpoint(id = "leaderElection")
class LeaderElectionStatusEndpoint private constructor(
    leaderElector: LeaderElector,
    private val registry: LeaderElectionStatusRegistry,
    private val acquisitionFailureWindow: LeaderAcquisitionFailureWindow?,
    @Suppress("UNUSED_PARAMETER")
    constructorMarker: Any?,
) {

    constructor(
        leaderElector: LeaderElector,
        registry: LeaderElectionStatusRegistry,
    ) : this(leaderElector, registry, null, null)

    internal constructor(
        leaderElector: LeaderElector,
        registry: LeaderElectionStatusRegistry,
        acquisitionFailureWindow: LeaderAcquisitionFailureWindow,
    ) : this(leaderElector, registry, acquisitionFailureWindow, Unit)

    private val stateProvider: LeaderElectionState = leaderElector
    private val selectedBackend: String =
        (leaderElector as? SelectedStateLeaderElector)?.backendName
            ?: leaderElector::class.simpleName
            ?: "unknown"
    private val selectedStateProviderBean: String =
        (leaderElector as? SelectedStateLeaderElector)?.beanName.orEmpty()

    /**
     * `leaderElectionStatus` 호출은 Spring Boot integration 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `annotation`, `auto-configuration`, `route guard`, `metric`, `example` 용어는 기존 계약과 동일하게 유지합니다.
     */
    @ReadOperation
    fun leaderElectionStatus(): LeaderElectionStatusResponse {
        val acquisitionFailures = runCatching {
            acquisitionFailureWindow?.view() ?: LeaderAcquisitionFailureView.empty()
        }.getOrElse {
            LeaderAcquisitionFailureView.empty()
        }

        return LeaderElectionStatusResponse(
            backend = selectedBackend,
            stateProviderBean = selectedStateProviderBean,
            stateSupported = stateProvider.supportsAuditLeaderState,
            locks = registry.snapshot().map { lockName ->
                if (stateProvider.supportsAuditLeaderState) {
                    val state = stateProvider.state(lockName)
                    LeaderElectionLockStatus(
                        name = lockName,
                        status = state.status.name,
                        leaderId = state.leader?.auditLeaderId,
                        leaseExpiry = state.leader?.leaseUntil,
                    )
                } else {
                    LeaderElectionLockStatus(
                        name = lockName,
                        status = STATUS_UNSUPPORTED,
                        leaderId = null,
                        leaseExpiry = null,
                    )
                }
            },
            acquisitionFailures = acquisitionFailures,
        )
    }

    companion object {
        /** `0.5.0`에서 공개된 내부 JVM descriptor를 새 failure window 경계 뒤로 연결합니다. */
        @JvmSynthetic
        internal fun fromSelectedState(
            backendName: String,
            stateProviderBean: String,
            state: io.bluetape4k.leader.LeaderElectionState,
            registry: LeaderElectionStatusRegistry,
        ): LeaderElectionStatusEndpoint = fromSelectedState(
            backendName = backendName,
            stateProviderBean = stateProviderBean,
            state = state,
            registry = registry,
            acquisitionFailureWindow = null,
        )

        @JvmSynthetic
        internal fun fromSelectedState(
            backendName: String,
            stateProviderBean: String,
            state: io.bluetape4k.leader.LeaderElectionState,
            registry: LeaderElectionStatusRegistry,
            acquisitionFailureWindow: LeaderAcquisitionFailureWindow? = null,
        ): LeaderElectionStatusEndpoint = acquisitionFailureWindow?.let { window ->
            LeaderElectionStatusEndpoint(
                SelectedStateLeaderElector(backendName, stateProviderBean, state),
                registry,
                window,
            )
        } ?: LeaderElectionStatusEndpoint(
            SelectedStateLeaderElector(backendName, stateProviderBean, state),
            registry,
        )

        private const val STATUS_UNSUPPORTED = "Unsupported"
    }
}

/** Internal adapter that keeps selector details out of the public endpoint constructor. */
private class SelectedStateLeaderElector(
    val backendName: String,
    val beanName: String,
    private val delegate: LeaderElectionState,
) : LeaderElector {
    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override fun state(lockName: String) = delegate.state(lockName)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        error("The observability state adapter cannot execute leader work")

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        CompletableFuture.failedFuture(
            UnsupportedOperationException("The observability state adapter cannot execute leader work")
        )
}

/**
 * `LeaderElectionStatusResponse`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property locks Spring Boot integration 계약에서 `locks` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property backend 선택된 leader election backend입니다.
 * @property stateProviderBean 선택된 상태 제공자 Bean 이름입니다.
 * @property stateSupported backend가 상태 감사를 지원하는지 나타냅니다.
 * @property acquisitionFailures 최근 backend 획득 실패의 bounded aggregate입니다.
 */
data class LeaderElectionStatusResponse(
    val locks: List<LeaderElectionLockStatus>,
    val backend: String = "unknown",
    val stateProviderBean: String = "",
    val stateSupported: Boolean = false,
    val acquisitionFailures: LeaderAcquisitionFailureView = LeaderAcquisitionFailureView.empty(),
) : Serializable {
    /** `0.5.0`에서 Kotlin 기본 인자 호출자가 사용한 synthetic constructor를 보존합니다. */
    @Deprecated("0.5.0 JVM ABI 호환성 생성자", level = DeprecationLevel.HIDDEN)
    @Suppress("UNUSED_PARAMETER")
    constructor(
        locks: List<LeaderElectionLockStatus>,
        backend: String?,
        stateProviderBean: String?,
        stateSupported: Boolean,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ) : this(
        locks = locks,
        backend = if (mask and 0x002 != 0) "unknown" else backend.requireNotNull("backend"),
        stateProviderBean = if (mask and 0x004 != 0) "" else stateProviderBean.requireNotNull("stateProviderBean"),
        stateSupported = if (mask and 0x008 != 0) false else stateSupported,
        acquisitionFailures = LeaderAcquisitionFailureView.empty(),
    )

    /** 0.4.0 공개 API의 네 인자 생성자 바이너리 호환성을 유지합니다. */
    constructor(
        locks: List<LeaderElectionLockStatus>,
        backend: String,
        stateProviderBean: String,
        stateSupported: Boolean,
    ) : this(
        locks = locks,
        backend = backend,
        stateProviderBean = stateProviderBean,
        stateSupported = stateSupported,
        acquisitionFailures = LeaderAcquisitionFailureView.empty(),
    )

    /** 0.4.0 공개 API의 단일 인자 생성자 호환성을 유지합니다. */
    constructor(locks: List<LeaderElectionLockStatus>) : this(
        locks = locks,
        backend = "unknown",
        stateProviderBean = "",
        stateSupported = false,
        acquisitionFailures = LeaderAcquisitionFailureView.empty(),
    )

    /** 0.4.0 공개 API의 네 인자 data class `copy` 진입점 바이너리 호환성을 유지합니다. */
    fun copy(
        locks: List<LeaderElectionLockStatus>,
        backend: String,
        stateProviderBean: String,
        stateSupported: Boolean,
    ): LeaderElectionStatusResponse = copy(
        locks = locks,
        backend = backend,
        stateProviderBean = stateProviderBean,
        stateSupported = stateSupported,
        acquisitionFailures = acquisitionFailures,
    )

    /** 0.4.0 공개 API의 단일 인자 data class `copy` 호환성을 유지합니다. */
    fun copy(locks: List<LeaderElectionLockStatus>): LeaderElectionStatusResponse = copy(
        locks = locks,
        backend = backend,
        stateProviderBean = stateProviderBean,
        stateSupported = stateSupported,
        acquisitionFailures = acquisitionFailures,
    )

    companion object {
        /** Preserves Kotlin's published single-argument `copy$default` descriptor. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderElectionStatusResponse,
            locks: List<LeaderElectionLockStatus>?,
            mask: Int,
            marker: Any?,
        ): LeaderElectionStatusResponse = self.copy(
            locks = if (mask and 0x001 != 0) self.locks else requireNotNull(locks),
        )

        /** Kotlin이 공개한 네 인자 `copy$default` descriptor의 바이너리 호환성을 유지합니다. */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER", "FunctionNaming")
        fun `copy$default`(
            self: LeaderElectionStatusResponse,
            locks: List<LeaderElectionLockStatus>?,
            backend: String?,
            stateProviderBean: String?,
            stateSupported: Boolean,
            mask: Int,
            marker: Any?,
        ): LeaderElectionStatusResponse = self.copy(
            locks = if (mask and 0x001 != 0) self.locks else requireNotNull(locks),
            backend = if (mask and 0x002 != 0) self.backend else requireNotNull(backend),
            stateProviderBean = if (mask and 0x004 != 0) self.stateProviderBean else requireNotNull(stateProviderBean),
            stateSupported = if (mask and 0x008 != 0) self.stateSupported else stateSupported,
            acquisitionFailures = self.acquisitionFailures,
        )

        private const val serialVersionUID = 1L
    }
}

/**
 * `LeaderElectionLockStatus`는 Spring Boot integration에서 사용하는 설정과 상태 값을 담는 데이터 모델입니다.
 *
 * @property name Spring Boot integration 계약에서 `name` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property status Spring Boot integration 계약에서 `status` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaderId Spring Boot integration 계약에서 `leaderId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property leaseExpiry Spring Boot integration 계약에서 `leaseExpiry` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
data class LeaderElectionLockStatus(
    val name: String,
    val status: String,
    val leaderId: String?,
    val leaseExpiry: Instant?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

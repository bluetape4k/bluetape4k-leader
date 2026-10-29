package io.bluetape4k.leader.spring.observability

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.leader.LeaderElectionState
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import java.io.Serializable
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.jvm.internal.DefaultConstructorMarker

/**
 * Actuator `leaderElection` read endpoint로 등록된 lock의 상태와 최근 획득 실패 aggregate를 반환합니다.
 *
 * @property registry 관찰할 lock 이름을 제공하는 status registry입니다.
 */
@Endpoint(id = "leaderElection")
class LeaderElectionStatusEndpoint private constructor(
    leaderElector: LeaderElector,
    private val registry: LeaderElectionStatusRegistry,
    private val acquisitionFailureWindow: LeaderAcquisitionFailureWindow?,
    @Suppress("UNUSED_PARAMETER")
    constructorMarker: Any?,
) {

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

    constructor(
        leaderElector: LeaderElector,
        registry: LeaderElectionStatusRegistry,
    ): this(leaderElector, registry, null, null)

    internal constructor(
        leaderElector: LeaderElector,
        registry: LeaderElectionStatusRegistry,
        acquisitionFailureWindow: LeaderAcquisitionFailureWindow,
    ): this(leaderElector, registry, acquisitionFailureWindow, Unit)

    private val stateProvider: LeaderElectionState = leaderElector
    private val selectedBackend: String =
        (leaderElector as? SelectedStateLeaderElector)?.backendName
            ?: leaderElector::class.simpleName
            ?: "unknown"
    private val selectedStateProviderBean: String =
        (leaderElector as? SelectedStateLeaderElector)?.beanName.orEmpty()

    /** 현재 backend, state provider, lock별 상태와 bounded acquisition-failure view를 반환합니다. */
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
}

/** Internal adapter that keeps selector details out of the public endpoint constructor. */
private class SelectedStateLeaderElector(
    val backendName: String,
    val beanName: String,
    private val delegate: LeaderElectionState,
): LeaderElector {

    private companion object: KLogging()

    override val supportsAuditLeaderState: Boolean
        get() = delegate.supportsAuditLeaderState

    override fun state(lockName: String) = delegate.state(lockName)

    override fun <T> runIfLeader(lockName: String, action: () -> T): T =
        error("The observability state adapter cannot execute leader work")

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> =
        failedCompletableFutureOf(
            UnsupportedOperationException("The observability state adapter cannot execute leader work")
        )

    override fun toString(): String {
        return ToStringBuilder(this)
            .add("backendName", backendName)
            .add("beanName", beanName)
            .add("supportsAuditLeaderState", delegate.supportsAuditLeaderState)
            .toString()
    }
}

/** Actuator status endpoint의 전체 응답입니다.
 *
 * @property locks registry snapshot 순서의 lock 상태 목록입니다.
 * @property backend 선택된 leader election backend 이름이며 알 수 없으면 `unknown`입니다.
 * @property stateProviderBean 선택된 state provider bean 이름이며 미지정이면 빈 문자열입니다.
 * @property stateSupported backend가 audit state를 지원하는지 여부입니다.
 * @property acquisitionFailures 최근 backend 획득 실패의 bounded aggregate입니다.
 */
data class LeaderElectionStatusResponse(
    val locks: List<LeaderElectionLockStatus>,
    val backend: String = "unknown",
    val stateProviderBean: String = "",
    val stateSupported: Boolean = false,
    val acquisitionFailures: LeaderAcquisitionFailureView = LeaderAcquisitionFailureView.empty(),
): Serializable {
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
    ): this(
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
    ): this(
        locks = locks,
        backend = backend,
        stateProviderBean = stateProviderBean,
        stateSupported = stateSupported,
        acquisitionFailures = LeaderAcquisitionFailureView.empty(),
    )

    /** 0.4.0 공개 API의 단일 인자 생성자 호환성을 유지합니다. */
    constructor(locks: List<LeaderElectionLockStatus>): this(
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

/** Actuator status endpoint가 반환하는 하나의 lock 상태입니다.
 *
 * @property name lock registry key입니다.
 * @property status backend state의 이름이며 audit state 미지원이면 `Unsupported`입니다.
 * @property leaderId 현재 leader의 audit ID이며 leader가 없거나 backend가 지원하지 않으면 `null`입니다.
 * @property leaseExpiry 현재 leader lease의 만료 시각이며 알 수 없으면 `null`입니다.
 */
data class LeaderElectionLockStatus(
    val name: String,
    val status: String,
    val leaderId: String?,
    val leaseExpiry: Instant?,
): Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

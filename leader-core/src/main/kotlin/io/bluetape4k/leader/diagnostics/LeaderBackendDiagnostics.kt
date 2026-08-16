package io.bluetape4k.leader.diagnostics

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Leader backend 기능의 지원 상태입니다. */
enum class LeaderBackendSupport {
    /** 기능을 지원합니다. */
    SUPPORTED,

    /** 기능을 지원하지 않습니다. */
    UNSUPPORTED,

    /** 현재 provider가 지원 여부를 확정할 수 없습니다. */
    UNKNOWN,
}

/** Leader backend 연결 상태입니다. */
enum class LeaderBackendConnectivityStatus {
    /** 기존 client가 backend에 연결된 상태임을 확인했습니다. */
    UP,

    /** 기존 client가 종료됐거나 연결되지 않은 상태임을 확인했습니다. */
    DOWN,

    /** 안전한 bounded 검사로 연결 상태를 확정하지 못했습니다. */
    UNKNOWN,

    /** connectivity probe를 요청하지 않았습니다. */
    NOT_CHECKED,
}

/** Lease 만료 계산의 기준 clock입니다. */
enum class LeaderBackendClockSource {
    /** 애플리케이션 process clock을 사용합니다. */
    PROCESS,

    /** Backend server clock을 사용합니다. */
    BACKEND,

    /** 설정에 따라 process 또는 backend clock을 사용합니다. */
    CONFIGURABLE,

    /** Clock 기반 만료가 적용되지 않습니다. */
    NOT_APPLICABLE,

    /** 현재 provider가 기준 clock을 확정할 수 없습니다. */
    UNKNOWN,
}

/** Leader backend의 TTL 또는 lease 만료 방식입니다. */
enum class LeaderBackendTtlMode {
    /** Client가 계산한 lease 만료 시각을 backend에 저장합니다. */
    CLIENT_LEASE,

    /** Backend가 TTL을 직접 관리합니다. */
    SERVER_TTL,

    /** Database timestamp 컬럼으로 lease 만료를 관리합니다. */
    DATABASE_TIMESTAMP,

    /** Backend session 수명으로 ownership을 관리합니다. */
    SESSION,

    /** TTL이 없습니다. */
    NONE,

    /** 현재 provider가 TTL 방식을 확정할 수 없습니다. */
    UNKNOWN,
}

/** Backend가 제공하는 leader election 실행 모델입니다. */
enum class LeaderExecutionModel {
    /** 호출 thread에서 실행하는 blocking API입니다. */
    BLOCKING,

    /** `CompletableFuture` 기반 async API입니다. */
    ASYNC,

    /** Kotlin coroutine suspend API입니다. */
    SUSPEND,

    /** Virtual thread에서 실행하는 API입니다. */
    VIRTUAL_THREAD,
}

/** Single election과 group election의 기능 지원 상태입니다. */
data class LeaderBackendModeSupport(
    val single: LeaderBackendSupport,
    val group: LeaderBackendSupport,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Leader backend의 정적 capability snapshot입니다.
 *
 * 이 모델을 생성하거나 조회할 때 외부 backend I/O를 실행하면 안 됩니다.
 */
data class LeaderBackendCapabilities(
    val singleExecutionModels: Set<LeaderExecutionModel>,
    val groupExecutionModels: Set<LeaderExecutionModel>,
    val leaseExtension: LeaderBackendModeSupport,
    val auditState: LeaderBackendModeSupport,
    val clockSource: LeaderBackendClockSource,
    val ttlMode: LeaderBackendTtlMode,
    val limitations: List<String> = emptyList(),
) : Serializable {

    init {
        limitations.forEachIndexed { index, limitation ->
            limitation.requireNotBlank("limitations[$index]")
        }
        require(limitations.distinct().size == limitations.size) {
            "limitations must not contain duplicates: $limitations"
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/** Backend를 식별하는 이름과 정적 capability를 묶은 descriptor입니다. */
data class LeaderBackendDescriptor(
    val backendId: String,
    val displayName: String,
    val capabilities: LeaderBackendCapabilities,
) : Serializable {

    init {
        backendId.requireNotBlank("backendId")
        displayName.requireNotBlank("displayName")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * 한 번의 connectivity probe 결과입니다.
 *
 * Raw exception, credential, endpoint, lock name은 이 모델에 저장하지 않습니다.
 */
data class LeaderBackendConnectivity(
    val status: LeaderBackendConnectivityStatus,
    val checkedAt: Instant? = null,
    val latencyMillis: Long? = null,
) : Serializable {

    init {
        latencyMillis?.let { latency ->
            require(latency >= 0L) { "latencyMillis must not be negative: $latency" }
        }
        if (status == LeaderBackendConnectivityStatus.NOT_CHECKED) {
            require(checkedAt == null && latencyMillis == null) {
                "NOT_CHECKED connectivity must not contain checkedAt or latencyMillis"
            }
        } else {
            require(checkedAt != null) { "$status connectivity requires checkedAt" }
        }
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        /** Probe를 실행하지 않은 결과를 생성합니다. */
        fun notChecked(): LeaderBackendConnectivity =
            LeaderBackendConnectivity(LeaderBackendConnectivityStatus.NOT_CHECKED)

        /** 연결된 상태를 생성합니다. */
        fun up(checkedAt: Instant, latencyMillis: Long? = null): LeaderBackendConnectivity =
            LeaderBackendConnectivity(LeaderBackendConnectivityStatus.UP, checkedAt, latencyMillis)

        /** 연결되지 않은 상태를 생성합니다. */
        fun down(checkedAt: Instant, latencyMillis: Long? = null): LeaderBackendConnectivity =
            LeaderBackendConnectivity(LeaderBackendConnectivityStatus.DOWN, checkedAt, latencyMillis)

        /** 안전한 검사로 상태를 확정하지 못한 결과를 생성합니다. */
        fun unknown(checkedAt: Instant, latencyMillis: Long? = null): LeaderBackendConnectivity =
            LeaderBackendConnectivity(LeaderBackendConnectivityStatus.UNKNOWN, checkedAt, latencyMillis)
    }
}

/** 정적 backend descriptor와 선택적인 connectivity 결과입니다. */
data class LeaderBackendDiagnostics(
    val descriptor: LeaderBackendDescriptor,
    val connectivity: LeaderBackendConnectivity,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * Framework에 종속되지 않은 leader backend diagnostics SPI입니다.
 *
 * 구현은 기존 client의 수명주기를 재사용해야 하며 lock 획득, lease 변경, backend scan,
 * client 또는 background executor 생성을 수행하면 안 됩니다.
 */
interface LeaderBackendDiagnosticsProvider {

    /** 외부 I/O 없이 조회할 수 있는 backend descriptor입니다. */
    val backendDescriptor: LeaderBackendDescriptor

    /**
     * 주어진 [timeout] 안에서 연결 상태를 확인합니다.
     *
     * 안전한 bounded 검사를 제공하지 않는 구현은 `UNKNOWN`을 반환합니다.
     */
    fun checkConnectivity(timeout: Duration): LeaderBackendConnectivity {
        timeout.requirePositiveFiniteProbeTimeout()
        return LeaderBackendConnectivity.unknown(Clock.systemUTC().instant())
    }

    /**
     * Backend diagnostics snapshot을 반환합니다.
     *
     * [probe]가 `false`이면 connectivity 검사를 실행하지 않습니다.
     */
    fun diagnostics(
        probe: Boolean = false,
        timeout: Duration = DefaultProbeTimeout,
    ): LeaderBackendDiagnostics {
        val connectivity = if (probe) {
            timeout.requirePositiveFiniteProbeTimeout()
            checkConnectivity(timeout)
        } else {
            LeaderBackendConnectivity.notChecked()
        }
        return LeaderBackendDiagnostics(backendDescriptor, connectivity)
    }

    companion object {
        /** Connectivity probe의 기본 timeout입니다. */
        val DefaultProbeTimeout: Duration = 500.milliseconds
    }
}

private fun Duration.requirePositiveFiniteProbeTimeout() {
    require(isFinite() && this > Duration.ZERO) {
        "probe timeout must be positive and finite: $this"
    }
}

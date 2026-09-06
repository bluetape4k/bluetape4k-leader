package io.bluetape4k.leader.contract

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import kotlin.time.Duration

/**
 * Strategic backend conformance fixture가 구분하는 후보 레지스트리 종류입니다.
 *
 * Single과 group strategic election은 backend에서 서로 다른 namespace를 사용할 수 있으므로
 * fixture도 두 저장 경계를 독립적으로 검증합니다.
 */
enum class StrategicBackendKind {
    SINGLE,
    GROUP,
}

/**
 * 외부 strategic backend를 공통 conformance fixture에 연결하는 provider입니다.
 *
 * 구현은 같은 [StrategicBackendKind]의 blocking/suspend adapter가 동일한 backend namespace를
 * 관찰하도록 구성해야 합니다. [awaitCandidateExpiration]은 production API가 아니라 test
 * control입니다. provider는 client, namespace, credential과 test resource lifecycle을 소유합니다.
 */
interface StrategicBackendConformanceProvider : AutoCloseable {

    /** Blocking strategic adapter를 만듭니다. */
    fun blocking(kind: StrategicBackendKind, nodeId: String): BlockingStrategicBackend

    /** Suspend strategic adapter를 만듭니다. */
    fun suspending(kind: StrategicBackendKind, nodeId: String): SuspendStrategicBackend

    /**
     * 후보가 TTL로 만료되어 backend 조회에서 사라질 때까지 기다립니다.
     *
     * 실제 backend는 bounded polling을, fake backend는 test clock 진행을 사용할 수 있습니다.
     * 관리 API로 후보를 직접 삭제해서 TTL 계약을 우회하면 안 됩니다.
     */
    fun awaitCandidateExpiration(
        kind: StrategicBackendKind,
        lockName: String,
        nodeId: String,
        timeout: Duration,
    ): Boolean

    /** 외부 resource가 없는 provider를 위한 기본 no-op 구현입니다. */
    override fun close() = Unit
}

/**
 * Blocking single/group strategic interface를 같은 assertion surface로 연결하는 adapter입니다.
 *
 * [runIfLeader]는 provider가 선택한 결정론적 전략으로 현재 [nodeId]의 winner 여부를 평가해야
 * 합니다. 정상 contention은 예외가 아니라 `null`로 반환합니다.
 */
interface BlockingStrategicBackend {
    val nodeId: String

    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration)

    fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration)

    fun unregisterCandidate(lockName: String, nodeId: String)

    fun listCandidates(lockName: String): List<CandidateInfo>

    fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    fun <T> runIfLeader(lockName: String, action: () -> T): T?
}

/**
 * Suspend single/group strategic interface를 같은 assertion surface로 연결하는 adapter입니다.
 *
 * 구현은 coroutine cancellation을 삼키지 않아야 하며 blocking I/O가 필요하면 backend adapter가
 * dispatcher 경계를 제공해야 합니다.
 */
interface SuspendStrategicBackend {
    val nodeId: String

    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration)

    suspend fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration)

    suspend fun unregisterCandidate(lockName: String, nodeId: String)

    suspend fun listCandidates(lockName: String): List<CandidateInfo>

    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T?
}

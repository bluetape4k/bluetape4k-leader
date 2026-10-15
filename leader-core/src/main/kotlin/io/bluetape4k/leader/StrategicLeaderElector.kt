package io.bluetape4k.leader

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import kotlin.time.Duration

/**
 * 후보 정보를 등록·갱신하고 전략에 따라 단일 leader 작업을 실행하는 interface입니다.
 *
 */
interface StrategicLeaderElector {

    /** 이 elector가 후보를 등록할 때 사용하는 노드 식별자입니다. */
    val nodeId: String

    /**
     * 후보를 등록하거나 같은 node ID의 기존 후보 정보를 갱신합니다.
     * @param lockName 후보 집합을 구분하는 leader lock 이름입니다.
     * @param info 등록할 node ID, 실행 이력, metadata입니다.
     * @param ttl backend가 후보 만료에 적용할 TTL입니다. 기본값은 `Duration.ZERO`이며 실제 만료 처리는 backend 계약을 따릅니다.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /**
     * heartbeat용 후보 갱신입니다. 기존 결과 카운터와 실행 시각은 보존하고
     * `info.metadata`와 TTL만 갱신합니다.
     *
     * Redis와 Local 구현은 이 연산을 backend 원자 경계로 처리합니다. 다른 구현은
     * 호환성을 위해 `listCandidates`와 `registerCandidate` 조합으로 기존 후보를
     * 갱신할 수 있습니다. 후보가 조회되지 않으면 새로 등록하지 않고 no-op으로
     * 종료하며, 최초 등록은 `registerCandidate`가 담당합니다. 동시 결과 갱신이
     * 필요하면 backend 원자 구현을 제공해야 합니다.
     */
    fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO) {
        val current = listCandidates(lockName).firstOrNull { it.nodeId == info.nodeId }
        current?.let { registerCandidate(lockName, it.copy(metadata = info.metadata), ttl) }
    }

    /**
     * 지정한 후보를 등록 집합에서 제거합니다.
     * @param lockName 후보가 등록된 leader lock 이름입니다.
     * @param nodeId 제거할 후보의 식별자입니다.
     */
    fun unregisterCandidate(lockName: String, nodeId: String)

    /**
     * 지정한 lock 이름으로 등록된 후보 snapshot을 조회합니다.
     * @param lockName 후보 집합을 조회할 leader lock 이름입니다.
     * @return 등록된 후보 정보 목록입니다. 후보가 없으면 빈 목록입니다.
     */
    fun listCandidates(lockName: String): List<CandidateInfo>

    /**
     * 지정한 후보의 최근 작업 완료 시각과 성공·실패 횟수를 갱신합니다.
     * @param lockName 후보가 등록된 leader lock 이름입니다.
     * @param nodeId 결과를 기록할 후보 식별자입니다.
     * @param result 기록할 성공 또는 실패 결과입니다.
     */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * strategy가 선택한 후보만 작업을 실행합니다. 선택한 후보가 현재 노드가 아니면 `null`을 반환합니다.
     * @param lockName 후보 집합과 lock을 구분하는 이름입니다.
     * @param strategy 후보 정보로 실행 주체를 고르는 전략입니다.
     * @param options lock 획득 대기 시간, lease TTL 등 실행 정책입니다. 기본값은 `LeaderElectionOptions.Default`입니다.
     * @param action leadership을 획득한 경우에만 실행되는 사용자 작업입니다.
     * @return 선택한 후보가 현재 노드라면 작업 결과이고, 아니면 `null`입니다.
     */
    fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions = LeaderElectionOptions.Default,
        action: () -> T,
    ): T?
}

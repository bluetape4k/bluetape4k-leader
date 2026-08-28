package io.bluetape4k.leader

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import kotlin.time.Duration

/**
 * `StrategicLeaderGroupElector`는 후보 레지스트리에서 여러 leader를 전략적으로
 * 선택하는 blocking API입니다.
 *
 * `LeaderGroupElector`의 distributed slot claim과 달리 후보 기준 목록을
 * 읽고 `GroupElectionStrategy`로 결정론적인 winner 집합을 계산합니다. 따라서
 * `maxLeaders`는 관찰한 후보 기준 목록의 top-N이며 전역 동시 실행 상한이 아닙니다.
 * 새로운 distributed atomic claim은 제공하지 않습니다.
 */
interface StrategicLeaderGroupElector {

    /** 상태 조회와 후보 식별에 사용하는 노드 ID입니다. */
    val nodeId: String

    /** 후보를 등록하고 backend가 지원하는 경우 TTL을 적용합니다. */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /**
     * heartbeat용 후보 갱신입니다. 기존 결과 카운터와 실행 시각은 보존하고
     * `info.metadata`와 TTL만 갱신합니다.
     * Redis와 Local 구현은 이 연산을 backend 원자 경계로 처리합니다. 다른 구현은
     * 호환성을 위해 `listCandidates`와 `registerCandidate` 조합을 기본 동작으로
     * 사용할 수 있으므로, 동시 결과 갱신이 필요하면 backend 원자 구현을 제공해야 합니다.
     */
    fun refreshCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO) {
        val current = listCandidates(lockName).firstOrNull { it.nodeId == info.nodeId }
        registerCandidate(lockName, current?.copy(metadata = info.metadata) ?: info, ttl)
    }

    /** 후보를 등록 해제합니다. */
    fun unregisterCandidate(lockName: String, nodeId: String)

    /** 현재 backend에서 관찰되는 후보 목록을 반환합니다. */
    fun listCandidates(lockName: String): List<CandidateInfo>

    /** 후보 작업의 성공 또는 실패 결과를 registry에 반영합니다. */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * winner 목록에 현재 `nodeId`가 포함된 경우에만 action을 실행합니다.
     * 선택되지 않은 node는 action을 실행하지 않고 `null`을 반환합니다.
     *
     * `maxLeaders`는 관찰한 후보 기준 목록에서 선택할 최대 수이며 1 이상이어야
     * 합니다. 전역 분산 동시 실행 상한이 필요하면 `LeaderGroupElector`를 사용하세요.
     */
    fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int = LeaderGroupElectionOptions.DefaultMaxLeaders,
        action: () -> T,
    ): T?
}

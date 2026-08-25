package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import kotlin.time.Duration

/**
 * `StrategicSuspendLeaderGroupElector`는 후보 레지스트리에서 여러 leader를
 * 전략적으로 선택하는 coroutine API입니다.
 *
 * 후보 기준 목록을 읽고 `GroupElectionStrategy`로 winner 집합을 계산합니다.
 * 따라서 `maxLeaders`는 관찰한 후보 기준 목록의 top-N이며 전역 동시 실행 상한이
 * 아닙니다. 새로운 distributed atomic claim은 제공하지 않습니다.
 */
interface StrategicSuspendLeaderGroupElector {

    /** 상태 조회와 후보 식별에 사용하는 노드 ID입니다. */
    val nodeId: String

    /** 후보를 등록하고 backend가 지원하는 경우 TTL을 적용합니다. */
    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /** 후보를 등록 해제합니다. */
    suspend fun unregisterCandidate(lockName: String, nodeId: String)

    /** 현재 backend에서 관찰되는 후보 목록을 반환합니다. */
    suspend fun listCandidates(lockName: String): List<CandidateInfo>

    /** 후보 작업의 성공 또는 실패 결과를 registry에 반영합니다. */
    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * winner 목록에 현재 `nodeId`가 포함된 경우에만 action을 실행합니다.
     * 선택되지 않은 node는 action을 실행하지 않고 `null`을 반환합니다.
     *
     * `maxLeaders`는 관찰한 후보 기준 목록에서 선택할 최대 수이며 1 이상이어야
     * 합니다. 전역 분산 동시 실행 상한이 필요하면 `SuspendLeaderGroupElector`를 사용하세요.
     */
    suspend fun <T> runIfLeader(
        lockName: String,
        strategy: GroupElectionStrategy,
        maxLeaders: Int = LeaderGroupElectionOptions.DefaultMaxLeaders,
        action: suspend () -> T,
    ): T?
}

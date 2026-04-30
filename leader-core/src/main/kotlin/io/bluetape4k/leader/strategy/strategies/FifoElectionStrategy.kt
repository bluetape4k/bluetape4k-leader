package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionStrategy

/**
 * 가장 먼저 등록된 후보를 리더로 선출하는 FIFO 전략입니다.
 *
 * 동점(registeredAt 동일) 시 [CandidateInfo.nodeId] 사전순으로 결정합니다.
 */
object FifoElectionStrategy : ElectionStrategy {

    override fun selectLeader(candidates: List<CandidateInfo>): CandidateInfo? =
        candidates.minWithOrNull(
            compareBy(CandidateInfo::registeredAt).thenBy(CandidateInfo::nodeId)
        )
}

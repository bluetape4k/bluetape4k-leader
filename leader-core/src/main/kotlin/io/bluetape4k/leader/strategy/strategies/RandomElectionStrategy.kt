package io.bluetape4k.leader.strategy.strategies

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.ElectionResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.Elimination
import kotlin.random.Random

/**
 * 후보 중 무작위로 리더를 선출하는 전략입니다.
 *
 * [seed] 를 지정하면 동일 후보 목록에 대해 결정론적 결과를 보장합니다.
 *
 * **분산 환경 주의**: [seed] 가 `null` 이면 각 노드가 서로 다른 winner 를 계산할 수 있습니다 (split-brain 위험).
 * 분산 환경에서는 모든 노드가 동일한 [seed] 를 사용해야 합니다. seed 는 공유 백엔드(Redis 등)에서 epoch 단위로
 * 생성하여 배포하는 것을 권장합니다.
 * `seed=null` 은 단일 프로세스 테스트 또는 결과 분포가 중요하지 않은 경우에만 사용하세요.
 *
 * 무작위 선출 전 후보 목록을 [CandidateInfo.nodeId] 사전순으로 정렬하여 입력 순서 의존성을 제거합니다.
 *
 * @property seed 난수 seed. `null` 이면 시스템 랜덤 사용 (비결정론적 — 분산 환경 비권장).
 */
class RandomElectionStrategy(val seed: Long? = null) : ElectionStrategy {

    override fun elect(candidates: List<CandidateInfo>): ElectionResult {
        if (candidates.isEmpty()) return ElectionResult.EMPTY
        val sorted = candidates.sortedBy(CandidateInfo::nodeId)
        val random = if (seed != null) Random(seed) else Random.Default
        val winner = sorted[random.nextInt(sorted.size)]
        val eliminations = candidates
            .filter { it.nodeId != winner.nodeId }
            .map { c -> Elimination(c, "랜덤 선출 탈락 (winner: ${winner.nodeId})") }
        return ElectionResult(winner, eliminations)
    }
}

package io.bluetape4k.leader.strategy

import io.bluetape4k.support.requireGe

/**
 * custom strategy가 반환한 결과를 한 번 읽은 후보 기준 목록과 대조합니다.
 *
 * 이 검증은 action을 실행하기 전에 수행해 입력 외 후보, 중복 winner,
 * winner/elimination 교집합, 누락 후보, N 초과를 설정 오류로 즉시 드러냅니다.
 */
internal fun StrategicGroupElectionResult.validateAgainst(
    candidates: List<CandidateInfo>,
    maxLeaders: Int,
) {
    maxLeaders.requireGe(1, "maxLeaders")

    val candidateIds = candidates.map(CandidateInfo::nodeId)
    require(candidateIds.size == candidateIds.toSet().size) {
        "Candidate nodeId must be unique: $candidateIds"
    }
    val candidateIdSet = candidateIds.toSet()

    val winnerIds = winners.map(CandidateInfo::nodeId)
    require(winnerIds.size <= maxLeaders) {
        "Winner count must not exceed maxLeaders: winners=${winnerIds.size}, maxLeaders=$maxLeaders"
    }
    require(winnerIds.size == winnerIds.toSet().size) {
        "Winner nodeId must be unique: $winnerIds"
    }
    require(winnerIds.all(candidateIdSet::contains)) {
        "Winner nodeId must be present in candidates: $winnerIds"
    }

    val eliminationIds = eliminations.map { it.candidate.nodeId }
    require(eliminationIds.size == eliminationIds.toSet().size) {
        "Elimination nodeId must be unique: $eliminationIds"
    }
    require(eliminationIds.all(candidateIdSet::contains)) {
        "Elimination nodeId must be present in candidates: $eliminationIds"
    }
    require(winnerIds.intersect(eliminationIds.toSet()).isEmpty()) {
        "Winner and elimination nodeId must not overlap: winners=$winnerIds, eliminations=$eliminationIds"
    }
    require((winnerIds + eliminationIds).toSet() == candidateIdSet) {
        "Every candidate must be a winner or elimination: candidates=$candidateIds, " +
            "winners=$winnerIds, eliminations=$eliminationIds"
    }
    require(scores.keys.all(candidateIdSet::contains)) {
        "Score nodeId must be present in candidates: ${scores.keys}"
    }
}

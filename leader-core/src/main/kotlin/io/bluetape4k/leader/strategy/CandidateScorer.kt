package io.bluetape4k.leader.strategy

/**
 * 후보 노드에 대해 선출 우선순위 점수를 계산하는 인터페이스입니다.
 *
 * 점수가 높을수록 리더로 선출될 가능성이 높습니다.
 * [ScoredElectionStrategy] 에서 사용됩니다.
 */
fun interface CandidateScorer {

    /**
     * 후보 [candidate] 의 점수를 계산합니다.
     *
     * @param candidate 점수를 계산할 후보
     * @param all 현재 선출에 참여 중인 전체 후보 목록 (상대 비교에 활용 가능)
     * @return 점수 (높을수록 우선)
     */
    fun score(candidate: CandidateInfo, all: List<CandidateInfo>): Double
}

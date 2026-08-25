package io.bluetape4k.leader.strategy

import io.bluetape4k.support.requireGe

/**
 * `GroupElectionStrategy`는 후보 목록에서 전략적으로 복수 leader를 선택합니다.
 *
 * `ElectionStrategy`의 단일 승자 계약과 ABI를 유지하기 위해 group 선출은
 * 별도 계약으로 제공합니다. 반환되는 winner 순서는 선출 우선순위입니다.
 */
fun interface GroupElectionStrategy {

    /**
     * 주어진 후보 목록에서 최대 `maxLeaders`개의 후보를 결정론적으로 선택합니다.
     *
     * @param candidates 같은 선출 라운드에서 평가할 후보 목록입니다.
     * @param maxLeaders 선택할 수 있는 최대 leader 수입니다.
     * @return winner, elimination, score를 담은 선출 결과입니다.
     */
    fun elect(candidates: List<CandidateInfo>, maxLeaders: Int): StrategicGroupElectionResult
}

/**
 * 전략을 실행하고 반환 결과를 후보 기준 목록과 함께 검증합니다.
 *
 * Local 및 Redis adapter는 이 공통 경계를 사용해 winner/elimination partition,
 * 후보 ID, `maxLeaders`, score 키 불변식을 동일하게 적용합니다.
 */
fun GroupElectionStrategy.electValidated(
    candidates: List<CandidateInfo>,
    maxLeaders: Int,
): StrategicGroupElectionResult {
    maxLeaders.requireGe(1, "maxLeaders")
    return elect(candidates, maxLeaders).also {
        it.validateAgainst(candidates, maxLeaders)
    }
}

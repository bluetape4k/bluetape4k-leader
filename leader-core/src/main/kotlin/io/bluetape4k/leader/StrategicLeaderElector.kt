package io.bluetape4k.leader

import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import java.time.Duration

/**
 * 플러그형 선출 전략을 지원하는 리더 선출 인터페이스입니다.
 *
 * 기존 [LeaderElector] 과 달리 분산 락 경쟁 대신 후보 목록 기반 선출 방식을 사용합니다.
 *
 * ## 선출 흐름
 * 1. [registerCandidate] 로 후보 등록
 * 2. [listCandidates] 로 현재 후보 목록 조회
 * 3. [ElectionStrategy.elect] 로 winner 결정
 * 4. winner 이면 action 실행, 아니면 null 반환
 *
 * ## 분산 일관성 주의
 * 모든 노드가 동일한 후보 목록에 결정론적 전략을 적용하면 동일 winner 를 계산합니다.
 * 단, 후보 등록/조회 시점 차이로 인한 불일치는 백엔드 구현에서 처리해야 합니다.
 *
 * ## 사용 예제
 *
 * ```kotlin
 * val election = LocalStrategicLeaderElection("node-1")
 *
 * // 1. 후보 등록 — 분산 환경에서는 heartbeat 주기로 갱신
 * election.registerCandidate("nightly-job", CandidateInfo(election.nodeId))
 *
 * // 2. ScoredElectionStrategy + IdleTimeScorer — 가장 오래 쉰 노드 선출
 * val strategy = ScoredElectionStrategy(IdleTimeScorer)
 *
 * // 3. 선출되면 action 실행, 아니면 null
 * val result: Report? = election.runIfLeader("nightly-job", strategy) {
 *     generateNightlyReport()
 * }
 * ```
 */
interface StrategicLeaderElector {

    /** 이 인스턴스가 나타내는 노드 식별자 */
    val nodeId: String

    /**
     * 후보 등록 또는 갱신.
     *
     * [ttl] = [Duration.ZERO] 이면 TTL 없음 (Local 구현은 무시).
     * 분산 백엔드에서는 heartbeat 주기의 2배 이상으로 설정 권장.
     */
    fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /** 후보 등록 해제 */
    fun unregisterCandidate(lockName: String, nodeId: String)

    /** [lockName] 에 등록된 현재 후보 목록 조회 */
    fun listCandidates(lockName: String): List<CandidateInfo>

    /**
     * 작업 결과를 후보 정보에 반영합니다.
     * [result] 에 따라 [CandidateInfo.successCount] 또는 [CandidateInfo.failureCount] 가 증가합니다.
     */
    fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * 전략으로 리더를 선출하고 winner 인 경우에만 [action] 을 실행합니다.
     *
     * @param lockName 선출 식별자
     * @param strategy 선출 전략
     * @param options 선출 옵션 (waitTime, leaseTime)
     * @param action 리더 획득 성공 시 실행할 작업
     * @return [action] 실행 결과, 선출 실패 또는 다른 노드가 winner 이면 `null`
     */
    fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions = LeaderElectionOptions.Default,
        action: () -> T,
    ): T?
}

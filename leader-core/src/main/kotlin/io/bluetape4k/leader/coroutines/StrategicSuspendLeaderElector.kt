package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import java.time.Duration

/**
 * 코루틴 기반 플러그형 선출 전략을 지원하는 리더 선출 인터페이스입니다.
 *
 * [io.bluetape4k.leader.StrategicLeaderElector] 의 suspend 버전입니다.
 *
 * [action] 실행 중 코루틴 취소 시 `CancellationException` 은 반드시 호출자에게 재전파해야 합니다.
 */
interface StrategicSuspendLeaderElector {

    /** 이 인스턴스가 나타내는 노드 식별자 */
    val nodeId: String

    /**
     * 후보 등록 또는 갱신.
     *
     * [ttl] = [Duration.ZERO] 이면 TTL 없음 (Local 구현은 무시).
     */
    suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration = Duration.ZERO)

    /** 후보 등록 해제 */
    suspend fun unregisterCandidate(lockName: String, nodeId: String)

    /** [lockName] 에 등록된 현재 후보 목록 조회 */
    suspend fun listCandidates(lockName: String): List<CandidateInfo>

    /**
     * 작업 결과를 후보 정보에 반영합니다.
     */
    suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult)

    /**
     * 전략으로 리더를 선출하고 winner 인 경우에만 suspend [action] 을 실행합니다.
     *
     * @param lockName 선출 식별자
     * @param strategy 선출 전략
     * @param options 선출 옵션
     * @param action 리더 획득 성공 시 실행할 suspend 작업
     * @return [action] 실행 결과, 선출 실패 또는 다른 노드가 winner 이면 `null`
     */
    suspend fun <T> runIfLeader(
        lockName: String,
        strategy: ElectionStrategy,
        options: LeaderElectionOptions = LeaderElectionOptions.Default,
        action: suspend () -> T,
    ): T?
}

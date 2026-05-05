package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElection
import io.bluetape4k.leader.LeaderElectionFactory
import io.bluetape4k.leader.LeaderElectionOptions

/**
 * [LocalLeaderElection] 팩토리 — `ReentrantLock` 기반 단일 JVM 리더 선출 인스턴스 생성.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LocalLeaderElectionFactory()
 * val election = factory.create(LeaderElectionOptions.Default)
 * val result = election.runIfLeader("job-lock") { "done" }
 * ```
 *
 * 모든 호출이 새 [LocalLeaderElection] 인스턴스를 반환한다. 동일 락 이름의 직렬화는
 * [AbstractLocalLeaderElection] 내부에서 정적으로 공유되는 락 맵으로 보장되므로 인스턴스가
 * 달라도 동일 lockName 간 상호 배제는 유지된다.
 */
class LocalLeaderElectionFactory : LeaderElectionFactory {

    override fun create(options: LeaderElectionOptions): LeaderElection =
        LocalLeaderElection(options)
}

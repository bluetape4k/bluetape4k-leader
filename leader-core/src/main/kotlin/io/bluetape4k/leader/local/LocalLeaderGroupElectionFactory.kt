package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderGroupElection
import io.bluetape4k.leader.LeaderGroupElectionFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * [LocalLeaderGroupElection] 팩토리 — Semaphore 기반 단일 JVM 다중 리더 선출 인스턴스 생성.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LocalLeaderGroupElectionFactory()
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 */
class LocalLeaderGroupElectionFactory : LeaderGroupElectionFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElection =
        LocalLeaderGroupElection(options)
}

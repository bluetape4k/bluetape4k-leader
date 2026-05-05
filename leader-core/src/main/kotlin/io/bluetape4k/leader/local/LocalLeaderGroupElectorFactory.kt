package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectorFactory
import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * [LocalLeaderGroupElector] 팩토리 — Semaphore 기반 단일 JVM 다중 리더 선출 인스턴스 생성.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LocalLeaderGroupElectionFactory()
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 */
class LocalLeaderGroupElectorFactory : LeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): LeaderGroupElector =
        LocalLeaderGroupElector(options)
}

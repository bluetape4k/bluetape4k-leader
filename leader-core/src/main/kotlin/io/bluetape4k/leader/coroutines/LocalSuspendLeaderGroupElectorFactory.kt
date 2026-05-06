package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * [LocalSuspendLeaderGroupElector] 팩토리 — `kotlinx.coroutines.sync.Semaphore` 기반 단일 JVM suspend 복수 리더 선출 인스턴스 생성.
 *
 * ## 사용 예
 * ```kotlin
 * val factory = LocalSuspendLeaderGroupElectorFactory()
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 */
class LocalSuspendLeaderGroupElectorFactory : SuspendLeaderGroupElectorFactory {

    override fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector =
        LocalSuspendLeaderGroupElector(options)
}

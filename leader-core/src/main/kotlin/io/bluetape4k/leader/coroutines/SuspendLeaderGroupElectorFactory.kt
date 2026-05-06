package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions

/**
 * 호출 단위 옵션으로 [SuspendLeaderGroupElector] 인스턴스를 생성하는 팩토리 SPI.
 *
 * ## 사용 예
 * ```kotlin
 * val factory: SuspendLeaderGroupElectorFactory = LocalSuspendLeaderGroupElectorFactory()
 * val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3, waitTime = 3.seconds))
 * val result = elector.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @see SuspendLeaderElectorFactory 단일 리더 suspend 팩토리
 * @see io.bluetape4k.leader.LeaderGroupElectorFactory sync 버전 팩토리
 */
fun interface SuspendLeaderGroupElectorFactory {

    /**
     * 주어진 [options]로 새로운 [SuspendLeaderGroupElector] 인스턴스를 생성한다.
     *
     * @param options 새 인스턴스에 적용할 옵션 (maxLeaders, waitTime, leaseTime)
     * @return 호출 단위 옵션이 적용된 [SuspendLeaderGroupElector] 인스턴스
     */
    suspend fun create(options: LeaderGroupElectionOptions): SuspendLeaderGroupElector
}

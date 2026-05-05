package io.bluetape4k.leader

/**
 * 호출 단위 옵션으로 [LeaderGroupElection] 인스턴스를 생성하는 팩토리 SPI.
 *
 * ## 도입 배경
 * 코어 인터페이스 [LeaderGroupElection.runIfLeader]는 `(lockName, action)` 시그니처만 제공하며
 * 옵션을 호출 단위로 받을 수단이 없다. AOP 어드바이스가 어노테이션 옵션 (`maxLeaders`,
 * `waitTime`, `leaseTime`) 마다 백엔드 인스턴스를 새로 생성/캐싱할 수 있도록 본 SPI를 추가한다.
 *
 * ## 사용 예
 * ```kotlin
 * val factory: LeaderGroupElectionFactory = RedissonLeaderGroupElectionFactory(redisson)
 * val election = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
 * val result = election.runIfLeader("batch-shard") { processChunk() }
 * ```
 *
 * @see LeaderElectionFactory 단일 리더 선출용 팩토리
 */
fun interface LeaderGroupElectionFactory {

    /**
     * 주어진 [options]로 새로운 [LeaderGroupElection] 인스턴스를 생성한다.
     *
     * @param options 새 인스턴스에 적용할 옵션 (maxLeaders, waitTime, leaseTime)
     * @return 호출 단위 옵션이 적용된 새 [LeaderGroupElection] 인스턴스
     */
    fun create(options: LeaderGroupElectionOptions): LeaderGroupElection
}

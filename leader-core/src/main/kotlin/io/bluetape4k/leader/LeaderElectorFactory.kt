package io.bluetape4k.leader

/**
 * 호출 단위 옵션으로 [LeaderElector] 인스턴스를 생성하는 팩토리 SPI.
 *
 * ## 도입 배경
 * 코어 인터페이스 [LeaderElector.runIfLeader]는 `(lockName, action)` 시그니처만 제공하며 옵션을
 * 호출 단위로 받을 수단이 없다. AOP 어드바이스가 어노테이션 옵션마다 백엔드 인스턴스를 새로
 * 생성/캐싱할 수 있도록 본 SPI를 추가한다.
 *
 * ## 사용 예
 * ```kotlin
 * val factory: LeaderElectionFactory = RedissonLeaderElectionFactory(redisson)
 * val election = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = election.runIfLeader("daily-job") { processData() }
 * ```
 *
 * ## 책임 분리
 * - 본 SPI: 호출자가 요청한 옵션으로 새 [LeaderElector] 인스턴스 생성
 * - 캐싱: AOP 레이어 또는 호출자 책임 (`ConcurrentHashMap<FactoryCacheKey, LeaderElection>` 등)
 * - 백엔드 클라이언트(`RedissonClient`, `MongoClient` 등) 수명: 호출자 또는 DI 컨테이너 책임
 *
 * @see LeaderGroupElectorFactory 그룹 리더 선출용 팩토리
 */
fun interface LeaderElectorFactory {

    /**
     * 주어진 [options]로 새로운 [LeaderElector] 인스턴스를 생성한다.
     *
     * 동일 [options]에 대해 매번 새 인스턴스를 반환할 수 있으며, 동일성 보장은 호출자 책임이다.
     *
     * @param options 새 인스턴스에 적용할 옵션 (waitTime, leaseTime)
     * @return 호출 단위 옵션이 적용된 새 [LeaderElector] 인스턴스
     */
    fun create(options: LeaderElectionOptions): LeaderElector
}

package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions

/**
 * 호출 단위 옵션으로 [SuspendLeaderElector] 인스턴스를 생성하는 팩토리 SPI.
 *
 * ## 도입 배경
 * [SuspendLeaderElector.runIfLeader]는 `(lockName, action)` 시그니처만 제공하며
 * 옵션을 호출 단위로 받을 수단이 없다. AOP 어드바이스가 어노테이션 옵션마다 suspend 백엔드
 * 인스턴스를 새로 생성/캐싱할 수 있도록 본 SPI를 추가한다.
 *
 * ## 사용 예
 * ```kotlin
 * val factory: SuspendLeaderElectorFactory = LocalSuspendLeaderElectorFactory()
 * val elector = factory.create(LeaderElectionOptions(waitTime = 3.seconds, leaseTime = 30.seconds))
 * val result = elector.runIfLeader("daily-job") { processData() }
 * ```
 *
 * @see SuspendLeaderGroupElectorFactory 복수 리더 suspend 팩토리
 * @see io.bluetape4k.leader.LeaderElectorFactory sync 버전 팩토리
 */
fun interface SuspendLeaderElectorFactory {

    /**
     * 주어진 [options]로 새로운 [SuspendLeaderElector] 인스턴스를 생성한다.
     *
     * @param options 새 인스턴스에 적용할 옵션 (waitTime, leaseTime)
     * @return 호출 단위 옵션이 적용된 [SuspendLeaderElector] 인스턴스
     */
    fun create(options: LeaderElectionOptions): SuspendLeaderElector
}

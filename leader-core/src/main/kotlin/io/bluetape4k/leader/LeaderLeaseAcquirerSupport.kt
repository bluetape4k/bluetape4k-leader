package io.bluetape4k.leader

/**
 * 기본 elector와 capability 보존 wrapper가 공유하는 작은 위임 표면입니다.
 * 구현체는 adapter를 지연 생성하므로 초기화가 끝나기 전의 elector를 관찰하지 않습니다.
 */
interface LeaderLeaseAcquirerSupport : LeaderLeaseAcquirer {
    val leaseAcquirerDelegate: LeaderLeaseAcquirer

    /** delegate가 실제 request-lease capability를 제공하는지 selector가 확인합니다. */
    val leaseCapabilityAvailable: Boolean
        get() = true

    override val configuredOptions: LeaderElectionOptions
        get() = leaseAcquirerDelegate.configuredOptions

    override fun tryAcquire(lockName: String): LeaderLeaseHandle? =
        leaseAcquirerDelegate.tryAcquire(lockName)

    override fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle? =
        leaseAcquirerDelegate.tryAcquire(slot)
}

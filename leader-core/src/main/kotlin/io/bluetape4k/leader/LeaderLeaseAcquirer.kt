package io.bluetape4k.leader

/**
 * 요청 경계에서 하나의 lease handle을 획득하는 capability입니다.
 *
 * 정상 contention은 예외가 아니라 `null`로 반환합니다. 구현체는
 * `lockName` overload에서 configured options의 node id를 audit identity로 사용하고,
 * `LeaderSlot` overload에서는 전달받은 identity를 보존해야 합니다.
 */
interface LeaderLeaseAcquirer {

    /** 이 capability가 캡처한 immutable election options입니다. */
    val configuredOptions: LeaderElectionOptions

    /** lock 이름과 구성된 node identity로 lease를 시도합니다. */
    fun tryAcquire(lockName: String): LeaderLeaseHandle?

    /** caller가 제공한 lock 이름과 audit identity로 lease를 시도합니다. */
    fun tryAcquire(slot: LeaderSlot): LeaderLeaseHandle?
}

package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot

/** 요청 경계에서 suspend lease handle을 획득하는 capability입니다. */
interface SuspendLeaderLeaseAcquirer {
    /** 이 capability가 캡처한 immutable election options입니다. */
    val configuredOptions: LeaderElectionOptions

    /** lock 이름과 구성된 node identity로 suspend lease를 시도합니다. */
    suspend fun tryAcquire(lockName: String): SuspendLeaderLeaseHandle?

    /** caller가 제공한 lock 이름과 identity로 suspend lease를 시도합니다. */
    suspend fun tryAcquire(slot: LeaderSlot): SuspendLeaderLeaseHandle?
}

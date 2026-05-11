package io.bluetape4k.leader.coroutines

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.contract.AbstractSuspendGroupLockExtenderContractTest
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractSuspendGroupLockExtenderContractTest] 의 Local backend 구현.
 *
 * [LocalSuspendLeaderGroupElector] 를 사용해 LockAssert / LockExtender × suspend group contract 를 검증한다.
 * 외부 인프라 불필요 — 단일 JVM 인메모리 실행.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalSuspendGroupLockExtenderContractTest : AbstractSuspendGroupLockExtenderContractTest() {

    override val elector: SuspendLeaderGroupElector =
        LocalSuspendLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
}

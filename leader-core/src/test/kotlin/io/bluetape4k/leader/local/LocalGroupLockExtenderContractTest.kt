package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractGroupLockExtenderContractTest] 의 Local backend 구현.
 *
 * [LocalLeaderGroupElector] 를 사용해 LockAssert / LockExtender × sync group contract 를 검증한다.
 * 외부 인프라 불필요 — 단일 JVM 인메모리 실행.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalGroupLockExtenderContractTest : AbstractGroupLockExtenderContractTest() {

    override val elector: LeaderGroupElector =
        LocalLeaderGroupElector(LeaderGroupElectionOptions(maxLeaders = 3))
}

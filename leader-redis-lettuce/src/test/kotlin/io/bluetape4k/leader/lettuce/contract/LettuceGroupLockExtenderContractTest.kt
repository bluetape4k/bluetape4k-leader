package io.bluetape4k.leader.lettuce.contract

import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.contract.AbstractGroupLockExtenderContractTest
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.leader.lettuce.LettuceLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.TestInstance

/**
 * [AbstractGroupLockExtenderContractTest] 의 Lettuce backend 구현 — T7 PR 2 (Issue #79).
 *
 * `maxLeaders = 2` 로 기본 설정. server-side TIME Lua 가 extendSlot 에서 호출됨 (AC-16).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceGroupLockExtenderContractTest : AbstractGroupLockExtenderContractTest() {

    companion object : KLogging() {
        val redis = AbstractLettuceLeaderTest.redis
    }

    override val elector: LeaderGroupElector =
        LettuceLeaderGroupElector(
            AbstractLettuceLeaderTest.connection,
            LeaderGroupElectionOptions(maxLeaders = 2),
        )
}

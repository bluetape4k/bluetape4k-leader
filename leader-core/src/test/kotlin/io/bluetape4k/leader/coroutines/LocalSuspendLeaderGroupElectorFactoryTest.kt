package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * [LocalSuspendLeaderGroupElectorFactory] — SPI contract 테스트.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalSuspendLeaderGroupElectorFactoryTest {

    companion object: KLoggingChannel()

    private val factory: SuspendLeaderGroupElectorFactory = LocalSuspendLeaderGroupElectorFactory()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    @Test
    fun `create - 기본 옵션으로 SuspendLeaderGroupElector 인스턴스 반환`() = runSuspendIO {
        val elector = factory.create(LeaderGroupElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<LocalSuspendLeaderGroupElector>()
    }

    @Test
    fun `create - 커스텀 maxLeaders 옵션으로 인스턴스 반환`() = runSuspendIO {
        val opts = LeaderGroupElectionOptions(maxLeaders = 5, waitTime = Duration.ofSeconds(1), leaseTime = Duration.ofSeconds(10))
        val elector = factory.create(opts)
        elector.shouldNotBeNull()
        elector.maxLeaders shouldBeEqualTo 5
    }

    @Test
    fun `create - 호출마다 새 인스턴스 반환`() = runSuspendIO {
        val a = factory.create(LeaderGroupElectionOptions.Default)
        val b = factory.create(LeaderGroupElectionOptions.Default)
        (a !== b).shouldBeEqualTo(true)
    }

    @Test
    fun `create 후 runIfLeader - 슬롯 획득 성공 시 action 실행`() = runSuspendIO {
        val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
        val result = elector.runIfLeader(randomLockName()) { "group-factory-ok" }
        result shouldBeEqualTo "group-factory-ok"
    }

    @Test
    fun `create 후 runIfLeader - activeCount 정상 동작`() = runSuspendIO {
        val lockName = randomLockName()
        val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
        elector.runIfLeader(lockName) {
            elector.activeCount(lockName) shouldBeEqualTo 1
        }
        elector.activeCount(lockName) shouldBeEqualTo 0
    }
}

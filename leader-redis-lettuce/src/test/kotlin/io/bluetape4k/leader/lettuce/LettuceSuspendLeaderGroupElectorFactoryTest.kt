package io.bluetape4k.leader.lettuce

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [LettuceSuspendLeaderGroupElectorFactory] — SPI contract 테스트.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceSuspendLeaderGroupElectorFactoryTest : AbstractLettuceLeaderTest() {

    companion object : KLogging()

    private val factory: SuspendLeaderGroupElectorFactory = LettuceSuspendLeaderGroupElectorFactory(connection)

    @Test
    fun `create - 기본 옵션으로 LettuceSuspendLeaderGroupElector 인스턴스 반환`() = runSuspendIO {
        val elector = factory.create(LeaderGroupElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<LettuceSuspendLeaderGroupElector>()
    }

    @Test
    fun `create - 커스텀 maxLeaders 옵션으로 인스턴스 반환`() = runSuspendIO {
        val opts = LeaderGroupElectionOptions(maxLeaders = 5, waitTime = 1.seconds, leaseTime = 10.seconds)
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
        val result = elector.runIfLeader(randomName()) { "group-factory-ok" }
        result shouldBeEqualTo "group-factory-ok"
    }

    @Test
    fun `create 후 runIfLeader - activeCount 정상 동작`() = runSuspendIO {
        val lockName = randomName()
        val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
        elector.runIfLeader(lockName) {
            elector.activeCount(lockName) shouldBeEqualTo 1
        }
        elector.activeCount(lockName) shouldBeEqualTo 0
    }
}

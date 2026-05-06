package io.bluetape4k.leader.redisson

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [RedissonSuspendLeaderElectorFactory] — SPI contract 테스트.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonSuspendLeaderElectorFactoryTest : AbstractRedissonLeaderTest() {

    companion object : KLogging()

    private val factory: SuspendLeaderElectorFactory = RedissonSuspendLeaderElectorFactory(redissonClient)

    @Test
    fun `create - 기본 옵션으로 RedissonSuspendLeaderElector 인스턴스 반환`() = runSuspendIO {
        val elector = factory.create(LeaderElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<RedissonSuspendLeaderElector>()
    }

    @Test
    fun `create - 커스텀 옵션으로 인스턴스 반환`() = runSuspendIO {
        val opts = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 10.seconds)
        val elector = factory.create(opts)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<RedissonSuspendLeaderElector>()
    }

    @Test
    fun `create - 호출마다 새 인스턴스 반환`() = runSuspendIO {
        val a = factory.create(LeaderElectionOptions.Default)
        val b = factory.create(LeaderElectionOptions.Default)
        (a !== b).shouldBeEqualTo(true)
    }

    @Test
    fun `create 후 runIfLeader - 리더 획득 성공 시 action 실행`() = runSuspendIO {
        val elector = factory.create(LeaderElectionOptions.Default)
        val result = elector.runIfLeader(randomName()) { "factory-ok" }
        result shouldBeEqualTo "factory-ok"
    }

    @Test
    fun `create 후 runIfLeader - Unit action 정상 실행`() = runSuspendIO {
        val elector = factory.create(LeaderElectionOptions.Default)
        var called = false
        elector.runIfLeader(randomName()) { called = true }
        called.shouldBeEqualTo(true)
    }
}

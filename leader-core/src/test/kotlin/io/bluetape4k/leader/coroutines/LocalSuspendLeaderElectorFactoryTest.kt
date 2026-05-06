package io.bluetape4k.leader.coroutines

import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration

/**
 * [LocalSuspendLeaderElectorFactory] — SPI contract 테스트.
 *
 * - `create()` 가 새 [SuspendLeaderElector] 인스턴스를 반환하는지 검증
 * - 반환된 인스턴스가 [runIfLeader] 를 정상 실행하는지 검증
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalSuspendLeaderElectorFactoryTest {

    companion object: KLoggingChannel()

    private val factory: SuspendLeaderElectorFactory = LocalSuspendLeaderElectorFactory()

    private fun randomLockName() = "lock-${Base58.randomString(8)}"

    @Test
    fun `create - 기본 옵션으로 SuspendLeaderElector 인스턴스 반환`() {
        val elector = factory.create(LeaderElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<LocalSuspendLeaderElector>()
    }

    @Test
    fun `create - 커스텀 옵션으로 인스턴스 반환`() {
        val opts = LeaderElectionOptions(waitTime = Duration.ofSeconds(1), leaseTime = Duration.ofSeconds(10))
        val elector = factory.create(opts)
        elector.shouldNotBeNull()
    }

    @Test
    fun `create - 호출마다 새 인스턴스 반환`() {
        val a = factory.create(LeaderElectionOptions.Default)
        val b = factory.create(LeaderElectionOptions.Default)
        // 팩토리는 매번 새 인스턴스를 반환 (동일성 보장 안 함)
        (a !== b).shouldBeEqualTo(true)
    }

    @Test
    fun `create 후 runIfLeader - 리더 획득 성공 시 action 실행`() = runSuspendIO {
        val elector = factory.create(LeaderElectionOptions.Default)
        val result = elector.runIfLeader(randomLockName()) { "factory-ok" }
        result shouldBeEqualTo "factory-ok"
    }

    @Test
    fun `create 후 runIfLeader - Unit action 정상 실행`() = runSuspendIO {
        val elector = factory.create(LeaderElectionOptions.Default)
        var called = false
        elector.runIfLeader(randomLockName()) { called = true }
        called.shouldBeEqualTo(true)
    }
}

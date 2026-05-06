package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderElectorFactory
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

/**
 * [ExposedR2DbcSuspendLeaderElectorFactory] — SPI contract 테스트.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedR2DbcSuspendLeaderElectorFactoryTest : AbstractExposedR2dbcLeaderTest() {

    companion object : KLoggingChannel()

    private fun makeFactory(testDB: TestR2dbcDB): SuspendLeaderElectorFactory {
        val db = setupDb(testDB)
        return ExposedR2DbcSuspendLeaderElectorFactory(db)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create - 기본 옵션으로 ExposedR2DbcSuspendLeaderElector 인스턴스 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val elector = factory.create(LeaderElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<ExposedR2DbcSuspendLeaderElector>()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create - 커스텀 옵션으로 인스턴스 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val opts = LeaderElectionOptions(waitTime = Duration.ofSeconds(1), leaseTime = Duration.ofSeconds(10))
        val elector = factory.create(opts)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<ExposedR2DbcSuspendLeaderElector>()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create - 호출마다 새 인스턴스 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val a = factory.create(LeaderElectionOptions.Default)
        val b = factory.create(LeaderElectionOptions.Default)
        (a !== b).shouldBeEqualTo(true)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create 후 runIfLeader - 리더 획득 성공 시 action 실행`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val elector = factory.create(LeaderElectionOptions.Default)
        val result = elector.runIfLeader(randomName()) { "factory-ok" }
        result shouldBeEqualTo "factory-ok"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create 후 runIfLeader - Unit action 정상 실행`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val elector = factory.create(LeaderElectionOptions.Default)
        var called = false
        elector.runIfLeader(randomName()) { called = true }
        called.shouldBeEqualTo(true)
    }
}

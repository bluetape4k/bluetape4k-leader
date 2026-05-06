package io.bluetape4k.leader.exposed.r2dbc

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.coroutines.SuspendLeaderGroupElectorFactory
import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInstanceOf
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [ExposedR2DbcSuspendLeaderGroupElectorFactory] — SPI contract 테스트.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExposedR2DbcSuspendLeaderGroupElectorFactoryTest : AbstractExposedR2dbcLeaderTest() {

    companion object : KLoggingChannel()

    private fun makeFactory(testDB: TestR2dbcDB): SuspendLeaderGroupElectorFactory {
        val db = setupDb(testDB)
        return ExposedR2DbcSuspendLeaderGroupElectorFactory(db)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create - 기본 옵션으로 ExposedR2DbcSuspendLeaderGroupElector 인스턴스 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val elector = factory.create(LeaderGroupElectionOptions.Default)
        elector.shouldNotBeNull()
        elector.shouldBeInstanceOf<ExposedR2DbcSuspendLeaderGroupElector>()
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create - 커스텀 maxLeaders 옵션으로 인스턴스 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val opts = LeaderGroupElectionOptions(maxLeaders = 5, waitTime = 1.seconds, leaseTime = 10.seconds)
        val elector = factory.create(opts)
        elector.shouldNotBeNull()
        elector.maxLeaders shouldBeEqualTo 5
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create - 호출마다 새 인스턴스 반환`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val a = factory.create(LeaderGroupElectionOptions.Default)
        val b = factory.create(LeaderGroupElectionOptions.Default)
        (a !== b).shouldBeEqualTo(true)
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create 후 runIfLeader - 슬롯 획득 성공 시 action 실행`(testDB: TestR2dbcDB) = runSuspendIO {
        val factory = makeFactory(testDB)
        val elector = factory.create(LeaderGroupElectionOptions(maxLeaders = 3))
        val result = elector.runIfLeader(randomName()) { "group-factory-ok" }
        result shouldBeEqualTo "group-factory-ok"
    }
}

package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * [ExposedJdbcLeaderGroupElectorFactory]의 group 옵션 전달 계약을 검증합니다.
 */
class ExposedJdbcLeaderGroupElectorFactoryTest: AbstractExposedJdbcLeaderTest() {

    companion object: KLogging()

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `create - useDbTime 옵션을 Exposed JDBC elector에 전달`(testDB: TestDB) {
        val db = connectDb(testDB)

        val elector = ExposedJdbcLeaderGroupElectorFactory(db)
            .create(LeaderGroupElectionOptions(maxLeaders = 3, useDbTime = true))
            .shouldBeInstanceOf<ExposedJdbcLeaderGroupElector>()

        log.debug { "leaderGroupOptions=${elector.options.leaderGroupOptions}" }
        elector.options.leaderGroupOptions.useDbTime.shouldBeTrue()
    }
}

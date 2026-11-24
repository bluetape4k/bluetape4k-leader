package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * Exposed JDBC virtual-thread group wrapper의 slot identity와 취소 계약을 검증합니다.
 */
class ExposedJdbcVirtualThreadLeaderGroupContractTest: AbstractExposedJdbcLeaderTest() {

    private fun options() = ExposedJdbcLeaderGroupElectionOptions(
        leaderGroupOptions = LeaderGroupElectionOptions(
            maxLeaders = 2,
            waitTime = 1.seconds,
            leaseTime = 10.seconds,
        ),
    )

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `slot 결과는 leaderId를 보존하고 슬롯을 재사용한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val elector = ExposedJdbcVirtualThreadLeaderGroupElector(
            ExposedJdbcLeaderGroupElector(db, options()),
        )
        val lockName = randomName()

        val first = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "exposed-group-a")) {
            "first"
        }.await(10.seconds)
        val firstElected = first.shouldBeInstanceOf<LeaderRunResult.Elected<*>>()
        firstElected.value shouldBeEqualTo "first"
        firstElected.leaderId shouldBeEqualTo "exposed-group-a"

        val second = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "exposed-group-b")) {
            "second"
        }.await(10.seconds)
        val secondElected = second.shouldBeInstanceOf<LeaderRunResult.Elected<*>>()
        secondElected.value shouldBeEqualTo "second"
        secondElected.leaderId shouldBeEqualTo "exposed-group-b"
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `virtual future 취소는 action을 중단하고 슬롯을 재사용하게 한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val elector = ExposedJdbcVirtualThreadLeaderGroupElector(
            ExposedJdbcLeaderGroupElector(db, options()),
        )
        val lockName = randomName()
        val actionStarted = CountDownLatch(1)
        val actionInterrupted = CountDownLatch(1)
        val release = CountDownLatch(1)

        val future = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "exposed-group-cancel")) {
            actionStarted.countDown()
            try {
                release.await()
                "should-not-complete"
            } catch (e: InterruptedException) {
                actionInterrupted.countDown()
                throw e
            }
        }

        try {
            actionStarted.await(10.seconds).shouldBeTrue()
            future.cancel(true).shouldBeTrue()
            actionInterrupted.await(10.seconds).shouldBeTrue()

            val reacquired = elector.runAsyncIfLeaderResult(LeaderSlot(lockName, "exposed-group-reacquired")) {
                "reacquired"
            }.await(10.seconds)
            val elected = reacquired.shouldBeInstanceOf<LeaderRunResult.Elected<*>>()
            elected.value shouldBeEqualTo "reacquired"
            elected.leaderId shouldBeEqualTo "exposed-group-reacquired"
        } finally {
            release.countDown()
        }
    }
}

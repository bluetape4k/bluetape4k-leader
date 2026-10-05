package io.bluetape4k.leader.examples.migration

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.get
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MigrationGateTest: AbstractMigrationGateTest() {

    companion object: KLogging()

    private fun isApplied(db: Database, migrationId: String): Boolean =
        transaction(db) {
            MigrationMarkerTable.selectAll()
                .where { MigrationMarkerTable.migrationId eq migrationId }
                .empty().not()
        }

    private fun markApplied(db: Database, migrationId: String) {
        transaction(db) {
            MigrationMarkerTable.insert {
                it[MigrationMarkerTable.migrationId] = migrationId
            }
        }
    }

    private fun gate(db: Database, lockName: String, nodeId: String = "test-node") =
        MigrationGate(
            db, MigrationGateOptions(
                nodeId = nodeId,
                lockName = lockName,
                waitTime = 2.seconds,
                leaseTime = 30.seconds,
            )
        )

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `단일 인스턴스 - 마이그레이션 실행 후 Migrated`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val migrationId = randomMigrationId()
        val migrated = AtomicInteger(0)

        val lockName = randomLockName()
        val outcome = gate(db, lockName)
            .runMigration(
                migrationId = migrationId,
                isApplied = { isApplied(db, migrationId) },
                migration = {
                    migrated.incrementAndGet()
                    markApplied(db, migrationId)
                },
            )

        outcome.shouldBeInstanceOf<Outcome.Migrated>()
        outcome.migrationId shouldBeEqualTo migrationId
        migrated.get() shouldBeEqualTo 1
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `이미 적용된 상태 - precheck로 AlreadyApplied (락 시도 없음)`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val migrationId = randomMigrationId()
        markApplied(db, migrationId)

        val migrated = AtomicInteger(0)

        val lockName = randomLockName()
        val outcome = gate(db, lockName)
            .runMigration(
                migrationId = migrationId,
                isApplied = { isApplied(db, migrationId) },
                migration = { migrated.incrementAndGet() },
            )

        outcome.shouldBeInstanceOf<Outcome.AlreadyApplied>()
        migrated.get() shouldBeEqualTo 0
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `3 인스턴스 동시 - 1 Migrated + 2 AlreadyApplied`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val migrationId = randomMigrationId()
        val lockName = randomLockName()
        val migrated = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(3)

        try {
            val outcomes = List(3) { idx ->
                executor.submit<Outcome> {
                    val gate = MigrationGate(
                        db, MigrationGateOptions(
                            nodeId = "node-$idx",
                            lockName = lockName,
                            waitTime = 5.seconds,
                            leaseTime = 30.seconds,
                        )
                    )
                    gate.runMigration(
                        migrationId = migrationId,
                        isApplied = { isApplied(db, migrationId) },
                        migration = {
                            migrated.incrementAndGet()
                            markApplied(db, migrationId)
                            Thread.sleep(Random.nextLong(100, 300))
                        },
                    )
                }
            }.map { it.get(15.seconds) }

            outcomes.forEach { log.debug { "outcome=$it" } }

            migrated.get() shouldBeEqualTo 1
            outcomes.count { it is Outcome.Migrated } shouldBeEqualTo 1
            outcomes.count { it is Outcome.AlreadyApplied } shouldBeEqualTo 2
        } finally {
            executor.shutdownNow()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `리더 마이그레이션 예외 - Failed (마커 미생성, 락 해제)`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val migrationId = randomMigrationId()
        val lockName = randomLockName()

        // 실패
        val outcome1 = gate(db, lockName, "fail-node")
            .runMigration(
                migrationId = migrationId,
                isApplied = { isApplied(db, migrationId) },
                migration = { throw IllegalStateException("DDL 실패") },
            )
        log.debug { "outcome1=$outcome1" }
        outcome1.shouldBeInstanceOf<Outcome.Failed>()
        outcome1.cause.shouldBeInstanceOf<IllegalStateException>()
        isApplied(db, migrationId).shouldBeFalse()

        // 차순위 인스턴스 takeover — 락이 해제되어 새로 획득 가능
        val migrated = AtomicInteger(0)
        val outcome2 = gate(db, lockName, "recover-node")
            .runMigration(
                migrationId = migrationId,
                isApplied = { isApplied(db, migrationId) },
                migration = {
                    migrated.incrementAndGet()
                    markApplied(db, migrationId)
                },
            )
        log.debug { "outcome2=$outcome2" }
        outcome2.shouldBeInstanceOf<Outcome.Migrated>()
        migrated.get() shouldBeEqualTo 1
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `waitTime 초과 + 마커 미생성 - Skipped`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val migrationId = randomMigrationId()
        val lockName = randomLockName()
        val leaderStarted = CountDownLatch(1)
        val leaderRelease = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            // 리더는 락 잡고 latch 대기 (마커 생성 안 함 — DDL 실행 중인 상황 시뮬레이션)
            val leaderFuture = executor.submit<Outcome> {
                MigrationGate(
                    db, MigrationGateOptions(
                        nodeId = "leader",
                        lockName = lockName,
                        waitTime = 100.milliseconds,
                        leaseTime = 30.seconds,
                    )
                ).runMigration(
                    migrationId = migrationId,
                    isApplied = { isApplied(db, migrationId) },
                    migration = {
                        leaderStarted.countDown()
                        leaderRelease.await(10.seconds)
                        // 마커 안 만들고 종료 — 후속 인스턴스가 Skipped 받도록
                    },
                )
            }

            leaderStarted.await(10.seconds)

            // 비리더는 짧은 waitTime — 락 못 잡고 마커도 없음 → Skipped
            val followerOutcome = MigrationGate(
                db, MigrationGateOptions(
                    nodeId = "follower",
                    lockName = lockName,
                    waitTime = 200.milliseconds,
                    leaseTime = 30.seconds,
                )
            ).runMigration(
                migrationId = migrationId,
                isApplied = { isApplied(db, migrationId) },
                migration = { error("not reached") },
            )

            leaderRelease.countDown()
            leaderFuture.get().shouldBeInstanceOf<Outcome.Migrated>()

            followerOutcome.shouldBeInstanceOf<Outcome.Skipped>()
        } finally {
            executor.shutdown()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `blank nodeId 또는 lockName - IllegalArgumentException`(testDB: TestDB) {
        connectDb(testDB)

        assertFailsWith<IllegalArgumentException> {
            MigrationGateOptions(nodeId = " ", lockName = "ok")
        }
        assertFailsWith<IllegalArgumentException> {
            MigrationGateOptions(nodeId = "ok", lockName = " ")
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `blank migrationId - IllegalArgumentException`(testDB: TestDB) {
        val db = connectDb(testDB)

        assertFailsWith<IllegalArgumentException> {
            gate(db, randomLockName()).runMigration(
                migrationId = "  ",
                isApplied = { false },
                migration = { },
            )
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `isApplied 예외 발생 - 마커 상태 불명을 Failed 로 매핑`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)

        val migrationId = randomMigrationId()
        val lockName = randomLockName()
        val migrated = AtomicInteger(0)

        val outcome = gate(db, lockName)
            .runMigration(
                migrationId = migrationId,
                isApplied = { error("마커 테이블 조회 실패 시뮬레이션") },
                migration = { migrated.incrementAndGet() },
            )

        outcome.shouldBeInstanceOf<Outcome.Failed>()
        outcome.cause.shouldBeInstanceOf<IllegalStateException>()

        // isApplied 예외 발생 시 마이그레이션 실행 안 됨
        migrated.get() shouldBeEqualTo 0
    }
}

package io.bluetape4k.leader.exposed.jdbc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.exposed.tests.TestDB
import io.bluetape4k.leader.exposed.jdbc.history.ExposedLeaderHistorySink
import io.bluetape4k.leader.exposed.tables.LeaderLockHistoryTable
import io.bluetape4k.leader.history.LeaderHistoryStatus
import io.bluetape4k.leader.history.SafeLeaderHistoryRecorder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.testcontainers.utility.Base58
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JdbcTransactionInterruptionContractTest : AbstractExposedJdbcLeaderTest() {

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `caller future 취소는 실행 중 JDBC statement를 자동 중단하지 않는다`(testDB: TestDB) {
        connectDb(testDB)

        RunningJdbcTransaction.start(testDB).use { running ->
            running.awaitActive()

            running.actionFuture.cancel(false).shouldBeTrue()
            running.actionFuture.isCancelled.shouldBeTrue()
            running.isActive().shouldBeTrue()

            running.requestRollbackAndCancel()
            running.awaitFinished()
            running.assertRolledBack()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `worker interrupt는 실행 중 JDBC statement cancel과 다른 계약이다`(testDB: TestDB) {
        connectDb(testDB)

        RunningJdbcTransaction.start(testDB).use { running ->
            running.awaitActive()

            running.interruptWorker()
            running.workerInterrupted().shouldBeTrue()
            running.isActive().shouldBeTrue()

            running.requestRollbackAndCancel()
            running.awaitFinished()
            running.contract.expectedTerminalInterruptFlag?.let { expected ->
                running.interruptObservedAtTerminal shouldBeEqualTo expected
            }
            running.assertRolledBack()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `Statement cancel은 실행 중 transaction을 rollback하고 driver 예외를 노출한다`(testDB: TestDB) {
        connectDb(testDB)

        RunningJdbcTransaction.start(testDB).use { running ->
            running.awaitActive()

            running.cancelStatement()
            running.awaitFinished()

            running.actionFuture.isCompletedExceptionally.shouldBeTrue()
            running.interruptObservedAtTerminal.shouldBeFalse()
            running.assertDriverCancelOutcome()
            running.assertRolledBack()
        }
    }

    @ParameterizedTest
    @MethodSource("enableDialects")
    fun `driver cancel 뒤 FAILED history를 한 번 기록하고 lock을 반환한다`(testDB: TestDB) {
        val db = connectDb(testDB)
        cleanTables(db)
        val lockName = randomName()
        val terminalCount = AtomicInteger()
        val runningRef = AtomicReference<RunningJdbcTransaction>()
        val election = ExposedJdbcLeaderElector(
            db,
            historyRecorder = SafeLeaderHistoryRecorder(ExposedLeaderHistorySink(db)),
        )

        val resultFuture = election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            RunningJdbcTransaction.start(testDB).also(runningRef::set).actionFuture
                .whenComplete { _, _ -> terminalCount.incrementAndGet() }
        }
        val running = awaitRunningTransaction(runningRef)

        running.use {
            running.awaitActive()
            running.cancelStatement()

            val failure = assertFailsWith<CompletionException> { resultFuture.join() }
            failure.cause?.javaClass?.name shouldBeEqualTo running.contract.exceptionClass
            (failure.cause as SQLException).sqlState shouldBeEqualTo running.contract.cancelSqlState
            running.awaitFinished()
            running.assertRolledBack()

            val histories = transaction(db) {
                LeaderLockHistoryTable
                    .selectAll()
                    .where { LeaderLockHistoryTable.lockName eq lockName }
                    .toList()
            }
            histories.size shouldBeEqualTo 1
            histories.single()[LeaderLockHistoryTable.status] shouldBeEqualTo LeaderHistoryStatus.FAILED.name
            terminalCount.get() shouldBeEqualTo 1
            election.runIfLeader(lockName) { "driver cancel 뒤 복구" } shouldBeEqualTo "driver cancel 뒤 복구"
        }
    }

    private fun awaitRunningTransaction(
        reference: AtomicReference<RunningJdbcTransaction>,
    ): RunningJdbcTransaction {
        val deadline = System.nanoTime() + WAIT_TIMEOUT_NANOS
        while (System.nanoTime() - deadline < 0L) {
            reference.get()?.let { return it }
            Thread.sleep(POLL_MILLIS)
        }
        error("JDBC action이 시작되지 않았습니다.")
    }

    private companion object {
        const val POLL_MILLIS = 10L
        val WAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5)
    }
}

private class RunningJdbcTransaction private constructor(
    val contract: JdbcDriverInterruptionContract,
) : AutoCloseable {

    private val marker = "issue855_${Base58.randomString(8).lowercase()}"
    private val tableName = "issue_855_probe_${Base58.randomString(8).lowercase()}"
    private val statementRef = AtomicReference<Statement>()
    private val connectionRef = AtomicReference<Connection>()
    private val workerRef = AtomicReference<Thread>()
    private val sessionIdRef = AtomicReference<Long>()
    private val failureRef = AtomicReference<Throwable>()
    private val finished = CountDownLatch(1)
    private val terminalInterrupt = AtomicBoolean()
    private val rollbackOnly = AtomicBoolean()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { command ->
        Thread(command, "issue-855-${contract.testDB.name.lowercase()}").also(workerRef::set)
    }

    val actionFuture = CompletableFuture<Unit>()

    val interruptObservedAtTerminal: Boolean
        get() = terminalInterrupt.get()

    init {
        createProbeTable()
        executor.execute(::runTransaction)
    }

    fun awaitActive() {
        awaitCondition("marker query가 active 상태가 되지 않았습니다.") { isActive() }
    }

    fun isActive(): Boolean {
        val sessionId = sessionIdRef.get() ?: return false
        return rawConnection().use { connection -> contract.activeQuery(connection, sessionId)?.contains(marker) == true }
    }

    fun cancelStatement() {
        checkNotNull(statementRef.get()) { "실행 중 Statement가 없습니다." }.cancel()
    }

    fun requestRollbackAndCancel() {
        rollbackOnly.set(true)
        cancelStatement()
    }

    fun interruptWorker() {
        checkNotNull(workerRef.get()) { "JDBC worker가 시작되지 않았습니다." }.interrupt()
    }

    fun workerInterrupted(): Boolean = checkNotNull(workerRef.get()).isInterrupted

    fun awaitFinished() {
        check(finished.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "JDBC transaction 종료를 기다리다 timeout이 발생했습니다." }
    }

    fun assertDriverCancelOutcome() {
        val failure = failureRef.get()
        check(failure is SQLException) { "SQLException이 필요하지만 ${failure?.javaClass?.name}을 관찰했습니다." }
        failure.javaClass.name shouldBeEqualTo contract.exceptionClass
        failure.sqlState shouldBeEqualTo contract.cancelSqlState
    }

    fun assertRolledBack() {
        rawConnection().use { connection ->
            connection.prepareStatement("SELECT probe_value FROM $tableName WHERE probe_id = 1").use { statement ->
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "probe row가 없습니다." }
                    resultSet.getInt(1) shouldBeEqualTo 0
                }
            }
        }
    }

    override fun close() {
        rollbackOnly.set(true)
        runCatching { statementRef.get()?.cancel() }
        if (!finished.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            runCatching { connectionRef.get()?.close() }
            check(finished.await(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "JDBC transaction을 cancel 또는 connection close로 종료하지 못했습니다."
            }
        }
        executor.shutdownNow()
        check(executor.awaitTermination(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "JDBC worker executor가 종료되지 않았습니다."
        }
        rawConnection().use { connection ->
            connection.createStatement().use { it.executeUpdate("DROP TABLE IF EXISTS $tableName") }
        }
    }

    private fun createProbeTable() {
        rawConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE $tableName (probe_id INTEGER PRIMARY KEY, probe_value INTEGER NOT NULL)",
                )
                statement.executeUpdate("INSERT INTO $tableName (probe_id, probe_value) VALUES (1, 0)")
            }
        }
    }

    private fun runTransaction() {
        var connection: Connection? = null
        try {
            val activeConnection = rawConnection()
            connection = activeConnection
            connectionRef.set(activeConnection)
            activeConnection.autoCommit = false
            sessionIdRef.set(contract.sessionId(activeConnection))
            activeConnection.prepareStatement("UPDATE $tableName SET probe_value = 1 WHERE probe_id = 1").use {
                it.executeUpdate()
            }
            activeConnection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statementRef.set(statement)
                statement.execute(contract.longRunningQuery(marker))
            }
            if (rollbackOnly.get() || actionFuture.isCancelled) activeConnection.rollback() else activeConnection.commit()
            actionFuture.complete(Unit)
        } catch (e: Throwable) {
            failureRef.set(e)
            terminalInterrupt.set(Thread.currentThread().isInterrupted)
            runCatching { connection?.rollback() }
            actionFuture.completeExceptionally(e)
        } finally {
            statementRef.set(null)
            runCatching { connection?.close() }
            connectionRef.set(null)
            finished.countDown()
        }
    }

    private fun rawConnection(): Connection {
        Class.forName(contract.testDB.driver)
        return DriverManager.getConnection(
            contract.testDB.connection(),
            contract.testDB.user,
            contract.testDB.pass,
        ).also(contract.testDB.afterConnection)
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
        while (System.nanoTime() - deadline < 0L) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        error(message)
    }

    companion object {
        private const val POLL_MILLIS = 10L
        private const val QUERY_TIMEOUT_SECONDS = 10
        private const val WAIT_TIMEOUT_SECONDS = 5L

        fun start(testDB: TestDB): RunningJdbcTransaction =
            RunningJdbcTransaction(JdbcDriverInterruptionContract.from(testDB))
    }
}

private enum class JdbcDriverInterruptionContract(
    val testDB: TestDB,
    val cancelSqlState: String?,
    val exceptionClass: String,
    val expectedTerminalInterruptFlag: Boolean?,
) {
    H2(
        TestDB.H2,
        cancelSqlState = "57014",
        exceptionClass = "org.h2.jdbc.JdbcSQLTimeoutException",
        expectedTerminalInterruptFlag = true,
    ),
    POSTGRESQL(
        TestDB.POSTGRESQL,
        cancelSqlState = "57014",
        exceptionClass = "org.postgresql.util.PSQLException",
        expectedTerminalInterruptFlag = true,
    ),
    MYSQL(
        TestDB.MYSQL_V8,
        cancelSqlState = null,
        exceptionClass = "com.mysql.cj.jdbc.exceptions.MySQLStatementCancelledException",
        expectedTerminalInterruptFlag = null,
    ),
    ;

    fun sessionId(connection: Connection): Long = connection.createStatement().use { statement ->
        statement.executeQuery(sessionIdQuery()).use { resultSet ->
            check(resultSet.next()) { "JDBC session id를 조회하지 못했습니다: $name" }
            resultSet.getLong(1)
        }
    }

    fun longRunningQuery(marker: String): String = when (this) {
        H2 -> "/* $marker */ SELECT SUM(RAND()) FROM SYSTEM_RANGE(1, 1000000000)"
        POSTGRESQL -> "/* $marker */ SELECT pg_sleep(30)"
        MYSQL -> "/* $marker */ SELECT SLEEP(30)"
    }

    fun activeQuery(connection: Connection, sessionId: Long): String? {
        connection.prepareStatement(activeQuerySql()).use { statement ->
            statement.setLong(1, sessionId)
            statement.executeQuery().use { resultSet ->
                return if (resultSet.next()) resultSet.getString(1) else null
            }
        }
    }

    private fun sessionIdQuery(): String = when (this) {
        H2 -> "SELECT SESSION_ID()"
        POSTGRESQL -> "SELECT pg_backend_pid()"
        MYSQL -> "SELECT CONNECTION_ID()"
    }

    private fun activeQuerySql(): String = when (this) {
        H2 -> "SELECT EXECUTING_STATEMENT FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID = ?"
        POSTGRESQL -> "SELECT query FROM pg_stat_activity WHERE pid = ? AND state = 'active'"
        MYSQL -> "SELECT INFO FROM INFORMATION_SCHEMA.PROCESSLIST WHERE ID = ?"
    }

    companion object {
        fun from(testDB: TestDB): JdbcDriverInterruptionContract = entries.single { it.testDB == testDB }
    }
}

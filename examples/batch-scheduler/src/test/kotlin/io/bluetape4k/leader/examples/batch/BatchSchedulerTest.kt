package io.bluetape4k.leader.examples.batch

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.closeSafe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BatchSchedulerTest: AbstractBatchSchedulerTest() {

    companion object: KLogging()

    @Test
    fun `단일 인스턴스 - run 호출 시 Job 실행 후 결과 반환`() {
        val connection = newConnection()
        try {
            val scheduler = BatchScheduler(
                nodeId = "single-node",
                connection = connection,
                lockName = randomLockName(),
            )
            val result = scheduler.run { 42 }
            result.shouldNotBeNull() shouldBeEqualTo 42
        } finally {
            connection.closeSafe()
        }
    }

    @Test
    fun `3 인스턴스 동시 실행 - 단 1개만 Job 실행 보장`() {
        val lockName = randomLockName()
        val executions = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val losersFinished = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(3)
        val connections = (1..3).map { newConnection() }

        try {
            val futures = connections.mapIndexed { idx, conn ->
                executor.submit {
                    val scheduler = BatchScheduler(
                        nodeId = "node-${idx + 1}",
                        connection = conn,
                        lockName = lockName,
                        waitTime = 500.milliseconds,
                        leaseTime = 10.seconds,
                    )
                    val result = scheduler.run {
                        executions.incrementAndGet()
                        // 리더는 두 패자가 락 획득 시도를 마칠 때까지 대기 (race-free)
                        losersFinished.await(5, TimeUnit.SECONDS)
                        "executed-by-node-${idx + 1}"
                    }
                    if (result != null) {
                        successCount.incrementAndGet()
                    } else {
                        losersFinished.countDown()
                    }
                }
            }

            futures.forEach { it.get(15, TimeUnit.SECONDS) }

            executions.get() shouldBeEqualTo 1
            successCount.get() shouldBeEqualTo 1
        } finally {
            executor.shutdown()
            connections.forEach { it.closeSafe() }
        }
    }

    @Test
    fun `리더 작업 완료 후 - 다음 호출도 새로 선출 가능`() {
        val connection = newConnection()
        try {
            val scheduler = BatchScheduler(
                nodeId = "seq-node",
                connection = connection,
                lockName = randomLockName(),
                waitTime = 100.milliseconds,
                leaseTime = 500.milliseconds,
            )

            val r1 = scheduler.run { "first" }
            val r2 = scheduler.run { "second" }

            r1.shouldNotBeNull() shouldBeEqualTo "first"
            r2.shouldNotBeNull() shouldBeEqualTo "second"
        } finally {
            connection.closeSafe()
        }
    }

    @Test
    fun `Job 예외 발생 시 - 예외가 호출자에게 전달되고 락 해제됨`() {
        val lockName = randomLockName()
        val conn1 = newConnection()
        val conn2 = newConnection()

        try {
            val s1 = BatchScheduler(
                nodeId = "fail-node",
                connection = conn1,
                lockName = lockName,
                waitTime = 100.milliseconds,
                leaseTime = 30.seconds,
            )

            assertFailsWith<IllegalStateException> {
                s1.run<Unit> { throw IllegalStateException("정산 중 오류") }
            }

            val s2 = BatchScheduler(
                nodeId = "recover-node",
                connection = conn2,
                lockName = lockName,
                waitTime = 2.seconds,
                leaseTime = 5.seconds,
            )
            val recovered = s2.run { "recovered" }
            recovered.shouldNotBeNull() shouldBeEqualTo "recovered"
        } finally {
            conn1.closeSafe()
            conn2.closeSafe()
        }
    }
}

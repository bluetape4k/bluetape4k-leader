package io.bluetape4k.leader.hazelcast

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HazelcastLeaderElectionTest: AbstractHazelcastLeaderTest() {

    companion object: KLogging()

    @Test
    fun `runIfLeader - 리더로 선출되어 action 을 실행하고 결과를 반환한다`() {
        val election = HazelcastLeaderElector(hazelcastClient)
        val result = election.runIfLeader(randomName()) { "hello" }
        result shouldBeEqualTo "hello"
    }

    @Test
    fun `runIfLeader - lock 이 이미 보유된 경우 waitTime 초과 시 null 을 반환한다`() {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
        )
        val election = HazelcastLeaderElector(hazelcastClient, shortWaitOptions)
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()

        holder.submit {
            election.runIfLeader(lockName) {
                lockAcquired.countDown()
                releaseLock.await(3, TimeUnit.SECONDS)
            }
        }

        try {
            lockAcquired.await(2, TimeUnit.SECONDS)
            val result = election.runIfLeader(lockName) { 1 }
            result.shouldBeNull()
        } finally {
            releaseLock.countDown()
            holder.shutdownNow()
        }
    }

    @Test
    fun `runIfLeader - 빠른 종료 시 minLeaseTime 동안 Hazelcast TTL 로 락을 보존한다`() {
        val lockName = randomName()
        val election = HazelcastLeaderElector(
            hazelcastClient,
            LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 3.seconds,
                minLeaseTime = 2.seconds,
            )
        )

        election.runIfLeader(lockName) { "done" } shouldBeEqualTo "done"
        election.runIfLeader(lockName) { "too-early" }.shouldBeNull()

        Thread.sleep(2_200)

        election.runIfLeader(lockName) { "after-min" } shouldBeEqualTo "after-min"
    }

    @Test
    fun `runIfLeader - 멀티스레드 환경에서 순차적으로 leader 작업이 실행된다`() {
        val lockName = randomName()
        val election = HazelcastLeaderElector(hazelcastClient)
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 1. task1=${task1.get()}" }
                    task1.incrementAndGet()
                    Thread.sleep(Random.nextLong(5, 10))
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 2. task2=${task2.get()}" }
                    task2.incrementAndGet()
                    Thread.sleep(Random.nextLong(5, 10))
                }
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `runIfLeader - Virtual Thread 환경에서 순차적으로 leader 작업이 실행된다`() {
        val lockName = randomName()
        val election = HazelcastLeaderElector(hazelcastClient)
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(16)
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 1. task1=${task1.get()}" }
                    task1.incrementAndGet()
                    Thread.sleep(Random.nextLong(5, 10))
                }
            }
            .add {
                election.runIfLeader(lockName) {
                    log.debug { "작업 2. task2=${task2.get()}" }
                    task2.incrementAndGet()
                    Thread.sleep(Random.nextLong(5, 10))
                }
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }

    @Test
    fun `runAsyncIfLeader - 리더로 선출되어 비동기 action 을 실행하고 결과를 반환한다`() {
        val lockName = randomName()
        val election = HazelcastLeaderElector(hazelcastClient)
        val latch = CountDownLatch(2)

        val future1 = futureOf {
            election.runAsyncIfLeader(lockName) {
                futureOf {
                    log.debug { "비동기 작업 1 시작" }
                    Thread.sleep(50)
                    latch.countDown()
                    42
                }
            }.join()
        }
        val future2 = futureOf {
            election.runAsyncIfLeader(lockName) {
                futureOf {
                    log.debug { "비동기 작업 2 시작" }
                    Thread.sleep(50)
                    latch.countDown()
                    43
                }
            }.join()
        }
        latch.await(5, TimeUnit.SECONDS)
        future1.get() shouldBeEqualTo 42
        future2.get() shouldBeEqualTo 43
    }

    @Test
    fun `runAsyncIfLeader - action 실패 후에도 lock 이 해제되어 다음 호출이 성공한다`() {
        val lockName = randomName()
        val options = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds)
        val election = HazelcastLeaderElector(hazelcastClient, options)

        runCatching {
            election.runAsyncIfLeader(lockName) {
                CompletableFuture.failedFuture<Int>(IllegalStateException("boom"))
            }.join()
        }

        val result = election.runAsyncIfLeader(lockName) { futureOf { 99 } }
            .get(3, TimeUnit.SECONDS)
        result shouldBeEqualTo 99
    }

    @Test
    fun `runAsyncIfLeader - 멀티스레드 환경에서 순차적으로 비동기 leader 작업이 실행된다`() {
        val lockName = randomName()
        val election = HazelcastLeaderElector(hazelcastClient)
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                election.runAsyncIfLeader(lockName) {
                    futureOf {
                        task1.incrementAndGet()
                        Thread.sleep(Random.nextLong(5, 10))
                    }
                }.join()
            }
            .add {
                election.runAsyncIfLeader(lockName) {
                    futureOf {
                        task2.incrementAndGet()
                        Thread.sleep(Random.nextLong(5, 10))
                    }
                }.join()
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `runAsyncIfLeader - Virtual Thread 환경에서 순차적으로 비동기 leader 작업이 실행된다`() {
        val lockName = randomName()
        val election = HazelcastLeaderElector(hazelcastClient)
        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(16)
            .add {
                election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    futureOf {
                        task1.incrementAndGet()
                        Thread.sleep(Random.nextLong(5, 10))
                    }
                }.join()
            }
            .add {
                election.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    futureOf {
                        task2.incrementAndGet()
                        Thread.sleep(Random.nextLong(5, 10))
                    }
                }.join()
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }
}

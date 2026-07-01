package io.bluetape4k.leader.redisson

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldBeNull
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class RedissonLeaderElectionTest: AbstractRedissonLeaderTest() {

    companion object: KLogging()

    @Test
    fun `run action if leader`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)

        val executor = Executors.newFixedThreadPool(Runtimex.availableProcessors)
        try {
            val countDownLatch = CountDownLatch(2)

            executor.run {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다." }
                    randomSleep(90, 100)
                    log.debug { "작업 1 을 종료합니다." }
                    countDownLatch.countDown()
                }
            }
            executor.run {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다." }
                    randomSleep(90, 100)
                    log.debug { "작업 2 을 종료합니다." }
                    countDownLatch.countDown()
                }
            }

            countDownLatch.await()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `run async action if leader`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)
        val countDownLatch = CountDownLatch(2)

        val future1 = futureOf {
            leaderElection.runAsyncIfLeader(lockName) {
                futureOf {
                    log.debug { "작업 1 을 시작합니다." }
                    randomSleep(90, 100)
                    log.debug { "작업 1 을 종료합니다." }
                    countDownLatch.countDown()
                    42
                }
            }.join()
        }
        val future2 = futureOf {
            leaderElection.runAsyncIfLeader(lockName) {
                futureOf {
                    log.debug { "작업 2 을 시작합니다." }
                    randomSleep(90, 100)
                    log.debug { "작업 2 을 종료합니다." }
                    countDownLatch.countDown()
                    43
                }
            }.join()
        }
        countDownLatch.await(5, TimeUnit.SECONDS)
        future1.get() shouldBeEqualTo 42
        future2.get() shouldBeEqualTo 43
    }

    @Test
    fun `run async action should release lock even when action fails`() {
        val lockName = randomName()
        val options = LeaderElectionOptions(
            waitTime = 1.seconds,
            leaseTime = 30.seconds,
        )
        val leaderElection = RedissonLeaderElector(redissonClient, options)

        assertFailsWith<CompletionException> {
            leaderElection
                .runAsyncIfLeader(lockName) {
                    CompletableFuture.failedFuture<Int>(IllegalStateException("boom"))
                }
                .join()
        }

        leaderElection
            .runAsyncIfLeader(lockName) { CompletableFuture.completedFuture(1) }
            .get(2, TimeUnit.SECONDS) shouldBeEqualTo 1
    }

    @Test
    fun `runAsyncIfLeader - second call acquires immediately after first future completes`() {
        val lockName = randomName()
        val options = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 5.seconds,
            minLeaseTime = Duration.ZERO,
        )
        val leaderElection = RedissonLeaderElector(redissonClient, options)

        val attempts = AtomicInteger(0)

        MultithreadingTester()
            .workers(4)
            .rounds(5)
            .add {
                val attempt = attempts.incrementAndGet()
                val attemptLockName = "$lockName-$attempt"

                val first = leaderElection.runAsyncIfLeader(attemptLockName) {
                    CompletableFuture.completedFuture("first-$attempt")
                }
                first.get(2, TimeUnit.SECONDS) shouldBeEqualTo "first-$attempt"

                val second = leaderElection.runAsyncIfLeader(attemptLockName) {
                    CompletableFuture.completedFuture("second-$attempt")
                }
                second.get(2, TimeUnit.SECONDS) shouldBeEqualTo "second-$attempt"
            }
            .run()

        attempts.get() shouldBeEqualTo 20
    }

    @Test
    fun `runAsyncIfLeader - sync throw releases lock before failed future completes`() {
        val lockName = randomName()
        val options = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 5.seconds,
            minLeaseTime = Duration.ZERO,
        )
        val leaderElection = RedissonLeaderElector(redissonClient, options)

        val failed = leaderElection.runAsyncIfLeader<String>(lockName) {
            throw IllegalStateException("sync throw")
        }

        assertFailsWith<CompletionException> {
            failed.join()
        }.cause shouldBeInstanceOf IllegalStateException::class

        leaderElection
            .runAsyncIfLeader(lockName) { CompletableFuture.completedFuture("recovered") }
            .get(2, TimeUnit.SECONDS) shouldBeEqualTo "recovered"
    }

    @Test
    fun `runIfLeaderResult - action 실패는 ActionFailed 로 분류한다`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)
        val failure = IllegalStateException("redisson-result-boom")

        val result = leaderElection.runIfLeaderResult(LeaderSlot(lockName, "redisson-node")) {
            throw failure
        }

        result shouldBeEqualTo LeaderRunResult.ActionFailed(failure)
    }

    @Test
    fun `runIfLeaderResult - CancellationException 은 ActionFailed 로 감싸지 않고 재전파한다`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)
        val cancellation = CancellationException("redisson-cancelled")

        val thrown = assertFailsWith<CancellationException> {
            leaderElection.runIfLeaderResult<Any?>(LeaderSlot(lockName, "redisson-node")) {
                throw cancellation
            }
        }

        thrown shouldBeEqualTo cancellation
    }

    @Test
    fun `autoExtend with minLeaseTime is allowed since LeaderLeaseAutoExtender owns the watchdog`() {
        // T8 PR 3: Redisson built-in watchdog 비활성 — LeaderLeaseAutoExtender 가 단일 watchdog.
        // 기존 init constraint 제거 — autoExtend 와 minLeaseTime 동시 사용 가능.
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(
            redissonClient,
            LeaderElectionOptions(
                leaseTime = 1.seconds,
                minLeaseTime = 100.milliseconds,
                autoExtend = true,
            )
        )
        leaderElection.runIfLeader(lockName) { "ok" } shouldBeEqualTo "ok"
    }

    @Test
    fun `autoExtend uses LeaderLeaseAutoExtender while action exceeds explicit leaseTime`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(
            redissonClient,
            LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 250.milliseconds,
                autoExtend = true,
            )
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val holder = executor.submit<String?> {
                leaderElection.runIfLeader(lockName) {
                    started.countDown()
                    release.await(1, TimeUnit.SECONDS)
                    "holder"
                }
            }

            started.await(1, TimeUnit.SECONDS)
            Thread.sleep(450)

            leaderElection.runIfLeader(lockName) { "contender" }.shouldBeNull()

            release.countDown()
            holder.get(2, TimeUnit.SECONDS) shouldBeEqualTo "holder"
            leaderElection.runIfLeader(lockName) { "after-release" } shouldBeEqualTo "after-release"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `run action should keep Redis TTL until minLeaseTime after fast return`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(
            redissonClient,
            LeaderElectionOptions(
                waitTime = 100.milliseconds,
                leaseTime = 2.seconds,
                minLeaseTime = 300.milliseconds,
            )
        )

        leaderElection.runIfLeader(lockName) { "done" } shouldBeEqualTo "done"

        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit<String?> {
                leaderElection.runIfLeader(lockName) { "too-early" }
            }.get(2, TimeUnit.SECONDS).shouldBeNull()

            Thread.sleep(450)

            executor.submit<String?> {
                leaderElection.runIfLeader(lockName) { "after-min" }
            }.get(2, TimeUnit.SECONDS) shouldBeEqualTo "after-min"
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `run action should return null when lock is not acquired`() {
        val lockName = randomName()
        val options = LeaderElectionOptions(
            waitTime = 100.milliseconds,
            leaseTime = 5.seconds,
        )
        val leaderElection = RedissonLeaderElector(redissonClient, options)
        val lockAcquired = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val lockHolder = Executors.newSingleThreadExecutor()

        lockHolder.submit {
            val lock = redissonClient.getLock(lockName)
            lock.lock(3, TimeUnit.SECONDS)
            lockAcquired.countDown()
            runCatching { releaseLock.await(2, TimeUnit.SECONDS) }
            if (lock.isHeldByCurrentThread) {
                lock.unlock()
            }
        }

        try {
            lockAcquired.await(1, TimeUnit.SECONDS)
            val result = leaderElection.runIfLeader(lockName) { 1 }
            result shouldBeEqualTo null
        } finally {
            releaseLock.countDown()
            lockHolder.shutdownNow()
        }
    }

    @Test
    fun `run action if leader in multi threading`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                    task1.incrementAndGet()
                    randomSleep()
                    log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                }
            }
            .add {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                    task2.incrementAndGet()
                    randomSleep()
                    log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                }
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `run action if leader in virtual threads`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        StructuredTaskScopeTester()
            .rounds(numThreads * roundsPerThread / 2)
            .add {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                    task1.incrementAndGet()
                    randomSleep()
                    log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                }
            }
            .add {
                leaderElection.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                    task2.incrementAndGet()
                    randomSleep()
                    log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                }
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }

    @Test
    fun `run async action if leader in multi threading`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread)
            .add {
                leaderElection.runAsyncIfLeader(lockName) {
                    futureOf {
                        log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                        task1.incrementAndGet()
                        randomSleep()
                        log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                        42
                    }
                }.join()
            }
            .add {
                leaderElection.runAsyncIfLeader(lockName) {
                    futureOf {
                        log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                        task2.incrementAndGet()
                        randomSleep()
                        log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                        43
                    }
                }.join()
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `run async action if leader in virtual threads`() {
        val lockName = randomName()
        val leaderElection = RedissonLeaderElector(redissonClient)

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        StructuredTaskScopeTester()
            .rounds(numThreads * roundsPerThread / 2)
            .add {
                leaderElection.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    futureOf {
                        log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                        task1.incrementAndGet()
                        randomSleep()
                        log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                        42
                    }
                }.join()
            }
            .add {
                leaderElection.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    futureOf {
                        log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                        task2.incrementAndGet()
                        randomSleep()
                        log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                        43
                    }
                }.join()
            }
            .run()

        task1.get() shouldBeGreaterThan 0
        task2.get() shouldBeGreaterThan 0
    }

    /**
     * [MultithreadingTester]를 사용하여 짧은 `waitTime` 환경에서 동시 리더 선출 경쟁을 테스트한다.
     *
     * 여러 스레드가 동일한 락 이름으로 [RedissonLeaderElector.runIfLeader]를 동시에 호출할 때,
     * 리더로 선출된 스레드는 카운터를 증가시키고,
     * 락 획득에 실패한 스레드는 [RedisException]을 안전하게 삼킨다.
     */
    @Test
    fun `동시 다수 스레드에서 runIfLeader 호출 시 성공하거나 RedisException 을 발생시킨다`() {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 5.seconds,
        )
        val leaderElection = RedissonLeaderElector(redissonClient, shortWaitOptions)
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(16)
            .rounds(4)
            .add {
                runCatching {
                    leaderElection.runIfLeader(lockName) {
                        successCount.incrementAndGet()
                        randomSleep(10, 30)
                    }
                }
                // RedisException(락 획득 실패) 또는 성공 — 둘 다 허용
            }
            .run()

        log.debug { "총 성공 횟수: ${successCount.get()}" }
    }

    /**
     * [StructuredTaskScopeTester]를 사용하여 Virtual Thread 환경에서
     * 동시 리더 선출 경쟁 시 [RedisException] 이 안전하게 처리되는지 검증한다.
     */
    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `Virtual Thread 에서 runIfLeader 호출 시 성공하거나 RedisException 을 안전하게 처리한다`() {
        val lockName = randomName()
        val shortWaitOptions = LeaderElectionOptions(
            waitTime = 50.milliseconds,
            leaseTime = 5.seconds,
        )
        val leaderElection = RedissonLeaderElector(redissonClient, shortWaitOptions)
        val successCount = AtomicInteger(0)

        StructuredTaskScopeTester()
            .rounds(32)
            .add {
                runCatching {
                    leaderElection.runIfLeader(lockName) {
                        successCount.incrementAndGet()
                        randomSleep(10, 30)
                    }
                }
                // RedisException(락 획득 실패) 또는 성공 — 둘 다 허용
            }
            .run()

        log.debug { "총 성공 횟수: ${successCount.get()}" }
    }

    private fun randomSleep(from: Long = 5L, until: Long = 10L) {
        Thread.sleep(Random.nextLong(from, until))
    }


}

package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.concurrent.get
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.utils.Runtimex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class RedissonLeaderElectionSupportTest: AbstractRedissonLeaderTest() {

    companion object: KLogging()

    @Test
    fun `run action if leader`() {
        val lockName = randomName()
        val executor = Executors.newFixedThreadPool(Runtimex.availableProcessors)

        try {
            val countDownLatch = CountDownLatch(2)

            executor.run {
                redissonClient.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다." }
                    Thread.sleep(Random.nextLong(50, 100))
                    log.debug { "작업 1 을 종료합니다." }
                    countDownLatch.countDown()
                }
            }

            executor.run {
                redissonClient.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다." }
                    Thread.sleep(Random.nextLong(50, 100))
                    log.debug { "작업 2 을 종료합니다." }
                    countDownLatch.countDown()
                }
            }

            countDownLatch.await(3.seconds)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `run async action if leader`() {
        val lockName = randomName()

        val future1 = futureOf {
            redissonClient.runAsyncIfLeader(lockName) {
                futureOf {
                    log.debug { "작업 1 을 시작합니다." }
                    Thread.sleep(Random.nextLong(50, 100))
                    log.debug { "작업 1 을 종료합니다." }
                    Thread.sleep(Random.nextLong(5, 10))
                    42
                }
            }.get(3.seconds)
        }

        val future2 = futureOf {
            redissonClient.runAsyncIfLeader(lockName) {
                futureOf {
                    log.debug { "작업 2 을 시작합니다." }
                    Thread.sleep(Random.nextLong(50, 100))
                    log.debug { "작업 2 을 종료합니다." }
                    Thread.sleep(Random.nextLong(5, 10))
                    43
                }
            }.get(3.seconds)
        }

        future1.get(3.seconds) shouldBeEqualTo 42
        future2.get(3.seconds) shouldBeEqualTo 43
    }

    @Test
    fun `run action if leader in multi threading`() {
        val lockName = randomName()

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread * 2)
            .add {
                redissonClient.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                    task1.incrementAndGet()
                    randomSleep()
                    log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                }
            }
            .add {
                redissonClient.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                    task2.incrementAndGet()
                    randomSleep()
                    log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                }
            }
            .run()

        log.debug { "task1=${task1.get()}, task2=${task2.get()}" }
        task1.get() shouldBeEqualTo numThreads * roundsPerThread
        task2.get() shouldBeEqualTo numThreads * roundsPerThread
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `run action if leader in virtual threading`() {
        val lockName = randomName()

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        StructuredTaskScopeTester()
            .rounds(numThreads * roundsPerThread)
            .add {
                redissonClient.runIfLeader(lockName) {
                    log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                    task1.incrementAndGet()
                    Thread.sleep(Random.nextLong(5, 10))
                    log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                }
            }
            .add {
                redissonClient.runIfLeader(lockName) {
                    log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                    task2.incrementAndGet()
                    Thread.sleep(Random.nextLong(5, 10))
                    log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                }
            }
            .run()

        log.debug { "task1=${task1.get()}, task2=${task2.get()}" }
        task1.get() shouldBeEqualTo numThreads * roundsPerThread
        task2.get() shouldBeEqualTo numThreads * roundsPerThread
    }

    @Test
    fun `run async action if leader in multi threading`() {
        val lockName = randomName()

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        val executor = Executors.newFixedThreadPool(Runtimex.availableProcessors)

        MultithreadingTester()
            .workers(numThreads)
            .rounds(roundsPerThread * 2)
            .add {
                redissonClient.runAsyncIfLeader(lockName, executor) {
                    futureOf {
                        log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                        task1.incrementAndGet()
                        log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                        randomSleep()
                        42
                    }
                }.join()
            }
            .add {
                redissonClient.runAsyncIfLeader(lockName, executor) {
                    futureOf {
                        log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                        task2.incrementAndGet()
                        log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                        randomSleep()
                        43
                    }
                }.join()
            }
            .run()

        executor.shutdownNow()

        log.debug { "task1=${task1.get()}, task2=${task2.get()}" }
        task1.get() shouldBeEqualTo numThreads * roundsPerThread
        task2.get() shouldBeEqualTo numThreads * roundsPerThread
    }

    @EnabledForJreRange(min = JRE.JAVA_21)
    @Test
    fun `run async action if leader in virtual threading`() {
        val lockName = randomName()

        val task1 = AtomicInteger(0)
        val task2 = AtomicInteger(0)
        val numThreads = 8
        val roundsPerThread = 4

        StructuredTaskScopeTester()
            .rounds(numThreads * roundsPerThread)
            .add {
                redissonClient.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    virtualFuture {
                        log.debug { "작업 1 을 시작합니다. task1=${task1.get()}" }
                        task1.incrementAndGet()
                        log.debug { "작업 1 을 종료합니다. task1=${task1.get()}" }
                        Thread.sleep(Random.nextLong(5, 10))
                        42
                    }.toCompletableFuture()
                }.get(5.seconds)
            }
            .add {
                redissonClient.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
                    virtualFuture {
                        log.debug { "작업 2 을 시작합니다. task2=${task2.get()}" }
                        task2.incrementAndGet()
                        log.debug { "작업 2 을 종료합니다. task2=${task2.get()}" }
                        Thread.sleep(Random.nextLong(5, 10))
                        43
                    }.toCompletableFuture()
                }.get(5.seconds)
            }
            .run()

        log.debug { "task1=${task1.get()}, task2=${task2.get()}" }
        task1.get() shouldBeEqualTo numThreads * roundsPerThread
        task2.get() shouldBeEqualTo numThreads * roundsPerThread
    }
}

package io.bluetape4k.leader.examples.batch

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.StringCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 3-인스턴스 배치 스케줄러 데모.
 *
 * 동일 lock 이름을 공유하는 3개의 [BatchScheduler] 인스턴스를 동시 실행하여,
 * 단 1개만 정산 Job 을 수행하는 모습을 시연한다.
 *
 * Testcontainers Redis 를 자동 기동.
 */
object BatchSchedulerDemo: KLogging() {

    @JvmStatic
    fun main(args: Array<String>) {
        val redis = RedisServer.Launcher.redis
        val client = RedisClient.create(redis.url).also {
            ShutdownQueue.register { runCatching { it.shutdown() } }
        }

        val executions = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(3)

        try {
            log.info { "=== 야간 정산 배치 데모 시작 ===" }
            log.info { "3개 인스턴스가 동시에 'nightly-settlement' lock 획득 시도" }

            val futures = (1..3).map { idx ->
                executor.submit {
                    val connection = client.connect(StringCodec.UTF8)
                    try {
                        val scheduler = BatchScheduler(
                            nodeId = "node-$idx",
                            connection = connection,
                            lockName = "nightly-settlement",
                        )
                        val outcome = scheduler.run {
                            log.info { "[node-$idx] 정산 처리 시작" }
                            Thread.sleep(500)
                            executions.incrementAndGet()
                            log.info { "[node-$idx] 정산 처리 완료" }
                        }
                        log.info { "[node-$idx] outcome=${if (outcome != null) "LEADER" else "SKIPPED"}" }
                    } finally {
                        connection.closeSafe()
                    }
                }
            }
            futures.forEach { it.get() }

            log.info { "=== 결과 ===" }
            log.info { "실제 실행된 인스턴스 수: ${executions.get()} (기대값: 1)" }
        } finally {
            executor.shutdown()
        }
    }
}

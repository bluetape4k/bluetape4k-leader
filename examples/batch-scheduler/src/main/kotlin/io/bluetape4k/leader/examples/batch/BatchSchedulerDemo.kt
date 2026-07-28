package io.bluetape4k.leader.examples.batch

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.leader.examples.support.startExampleContainer
import io.bluetape4k.support.closeSafe
import io.bluetape4k.testcontainers.storage.RedisServer
import io.bluetape4k.utils.ShutdownQueue
import io.lettuce.core.RedisClient
import io.lettuce.core.codec.StringCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * `BatchSchedulerDemo`는 example workflow의 leader election, route guard, metric, example workflow 계약을 설명합니다.
 *
 * 실행 동작은 유지하고 annotation, auto-configuration, metric, sample intent를 한국어로 문서화합니다.
 */
object BatchSchedulerDemo: KLogging() {

    @JvmStatic
    fun main(args: Array<String>) {
        val redis = startExampleContainer { reuse -> RedisServer(reuse = reuse) }
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

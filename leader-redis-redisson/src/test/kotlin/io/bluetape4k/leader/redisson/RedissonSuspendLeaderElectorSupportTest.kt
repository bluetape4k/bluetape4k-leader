package io.bluetape4k.leader.redisson

import io.bluetape4k.coroutines.support.log
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Test

class RedissonSuspendLeaderElectorSupportTest: AbstractRedissonLeaderTest() {

    companion object: KLoggingChannel()

    @Test
    fun `run suspend action if leader`() = runSuspendIO {
        val jobName = randomName()

        val jobs = listOf(
            launch {
                redissonClient.suspendRunIfLeader(jobName) {
                    log.debug { "작업 1 을 시작합니다." }
                    randomDelay(50, 100)
                    log.debug { "작업 1 을 종료합니다." }
                }
            }.log("Job 1"),

            launch {
                redissonClient.suspendRunIfLeader(jobName) {
                    log.debug { "작업 2 을 시작합니다." }
                    randomDelay(50, 100)
                    log.debug { "작업 2 을 종료합니다." }
                }
            }.log("Job 2"),
        )
        jobs.joinAll()
    }
}

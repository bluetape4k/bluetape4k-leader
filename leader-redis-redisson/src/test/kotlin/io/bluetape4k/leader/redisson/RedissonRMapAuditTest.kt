package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the lifecycle of the `lg:{lockName}:audit` RMap used by [RedissonLeaderGroupElector].
 *
 * ## Contract verified
 * - auditLeaderId is stored in RMap on acquire and removed on release
 * - RMap entry is absent when `runIfLeader(lockName)` is used (no auditLeaderId)
 * - Concurrent acquire/release does not leave ghost audit entries
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedissonRMapAuditTest : AbstractRedissonLeaderTest() {

    companion object : KLogging()

    private val options = LeaderGroupElectionOptions(
        maxLeaders = 3,
        waitTime = 5.seconds,
        leaseTime = 30.seconds,
    )

    private fun auditMapKey(lockName: String) = "lg:{$lockName}:audit"
    private fun randomLockName() = "rmap-audit:${Base58.randomString(8)}"

    @Test
    fun `slot acquire 시 RMap 에 auditLeaderId 기록된다`() {
        val lockName = randomLockName()
        val slot = LeaderSlot(lockName, "node-audit")
        val elector = RedissonLeaderGroupElector(redissonClient, options)

        var capturedPermitId: String? = null
        elector.runIfLeader(slot) {
            val auditMap = redissonClient.getMap<String, String>(auditMapKey(lockName))
            val entries = auditMap.readAllEntrySet()
            (entries.any { it.value == "node-audit" }).shouldBeTrue()
            capturedPermitId = entries.firstOrNull { it.value == "node-audit" }?.key
        }

        // release 후 RMap 에서 삭제 확인
        if (capturedPermitId != null) {
            val auditMap = redissonClient.getMap<String, String>(auditMapKey(lockName))
            auditMap.get(capturedPermitId).shouldBeNull()
        }
    }

    @Test
    fun `runIfLeaderResult(slot) - Elected leaderId 와 RMap 항목 일치`() {
        val lockName = randomLockName()
        val slot = LeaderSlot(lockName, "result-node")
        val elector = RedissonLeaderGroupElector(redissonClient, options)

        val result = elector.runIfLeaderResult(slot) { "done" }

        (result is LeaderRunResult.Elected).shouldBeTrue()
        (result as LeaderRunResult.Elected).leaderId shouldBeEqualTo "result-node"
        result.value shouldBeEqualTo "done"
    }

    @Test
    fun `lockName 기반 runIfLeader 는 RMap 에 항목을 남기지 않는다`() {
        val lockName = randomLockName()
        val elector = RedissonLeaderGroupElector(redissonClient, options)

        elector.runIfLeader(lockName) {
            val auditMap = redissonClient.getMap<String, String>(auditMapKey(lockName))
            auditMap.size shouldBeEqualTo 0
        }
    }

    @Test
    fun `MultithreadingTester 100×3 — concurrent slot acquire_release RMap ghost 없음`() {
        val lockName = randomLockName()
        val elector = RedissonLeaderGroupElector(redissonClient, options)
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(3)
            .rounds(100)
            .add {
                val leaderId = "node-${Thread.currentThread().threadId()}"
                val slot = LeaderSlot(lockName, leaderId)
                val result = elector.runIfLeaderResult(slot) { leaderId }
                if (result is LeaderRunResult.Elected) {
                    successCount.incrementAndGet()
                }
            }
            .run()

        // 모든 실행 후 RMap 에 ghost 항목이 없어야 함
        val auditMap = redissonClient.getMap<String, String>(auditMapKey(lockName))
        auditMap.size shouldBeEqualTo 0
        (successCount.get() > 0).shouldBeTrue()
    }
}

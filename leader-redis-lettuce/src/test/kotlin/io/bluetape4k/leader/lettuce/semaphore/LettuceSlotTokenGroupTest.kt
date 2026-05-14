package io.bluetape4k.leader.lettuce.semaphore

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.leader.lettuce.AbstractLettuceLeaderTest
import io.bluetape4k.logging.KLogging
import io.lettuce.core.codec.StringCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceSlotTokenGroupTest: AbstractLettuceLeaderTest() {

    companion object: KLogging() {
        private const val MAX_LEADERS = 3
    }

    private lateinit var group: LettuceSlotTokenGroup
    private lateinit var lockName: String

    @BeforeEach
    fun setup() {
        lockName = "slot-token:${Base58.randomString(8)}"
        group = LettuceSlotTokenGroup(connection, lockName, MAX_LEADERS)
    }

    @AfterEach
    fun teardown() {
        connection.sync().del(group.slotKey)
    }

    @Test
    fun `단일 acquire 시 token 을 반환한다`() {
        val token = group.tryAcquire(waitTime = 1.seconds, leaseTime = 5.seconds)
        token.shouldNotBeNull()
        token.length shouldBeEqualTo 22

        group.release(token, remainingMinLeaseMs = 0)
    }

    @Test
    fun `maxLeaders 동시 acquire 모두 성공한다`() {
        val tokens = (1..MAX_LEADERS).map {
            group.tryAcquire(waitTime = 1.seconds, leaseTime = 5.seconds)
        }
        tokens.forEach { it.shouldNotBeNull() }
        group.activeCount() shouldBeEqualTo MAX_LEADERS
        group.availableSlots() shouldBeEqualTo 0

        tokens.forEach { token -> group.release(token!!, remainingMinLeaseMs = 0) }
        group.activeCount() shouldBeEqualTo 0
    }

    @Test
    fun `maxLeaders+1 번째 acquire 는 null 을 반환한다`() {
        val tokens = (1..MAX_LEADERS).map {
            group.tryAcquire(waitTime = 1.seconds, leaseTime = 10.seconds)!!
        }

        val overflow = group.tryAcquire(waitTime = 200.milliseconds, leaseTime = 10.seconds)
        overflow.shouldBeNull()

        tokens.forEach { group.release(it, remainingMinLeaseMs = 0) }
    }

    @Test
    fun `release 즉시 슬롯이 해제된다`() {
        val token = group.tryAcquire(waitTime = 1.seconds, leaseTime = 10.seconds)!!
        group.activeCount() shouldBeEqualTo 1

        group.release(token, remainingMinLeaseMs = 0)
        group.activeCount() shouldBeEqualTo 0
    }

    @Test
    fun `remainingMinLeaseMs 가 양수이면 score 만 갱신되고 슬롯은 유지된다`() {
        val token = group.tryAcquire(waitTime = 1.seconds, leaseTime = 10.seconds)!!
        group.activeCount() shouldBeEqualTo 1

        // minLease 가 남아있는 release: 슬롯은 유지되어야 한다
        group.release(token, remainingMinLeaseMs = 800)
        group.activeCount() shouldBeEqualTo 1

        // 다른 client 가 동일 token 을 흉내내도 acquire 는 가능 (슬롯은 1개 점유 중, max 는 3)
        val secondToken = group.tryAcquire(waitTime = 200.milliseconds, leaseTime = 5.seconds)
        secondToken.shouldNotBeNull()
        group.release(secondToken, remainingMinLeaseMs = 0)

        // 원래 token 도 cleanup
        group.release(token, remainingMinLeaseMs = 0)
    }

    @Test
    fun `Codex P2-1 - tryAcquireAsync 는 backend error 가 deadline 까지 해소되지 않으면 future 가 fail 한다`() {
        // 별도 connection 을 만든 뒤 즉시 close → 모든 async ACQUIRE script call 이 backend error 로 실패.
        // 기존 구현은 error 를 null 로 변환 후 contention 으로 판단해 timeout 후 null 을 반환했지만,
        // P2-1 fix 후에는 마지막 error 가 future 로 propagate 되어야 한다.
        val deadConn = client.connect(StringCodec.UTF8)
        deadConn.close()

        val deadGroup = LettuceSlotTokenGroup(deadConn, "dead-test:${Base58.randomString(8)}", MAX_LEADERS)
        val future = deadGroup.tryAcquireAsync(waitTime = 200.milliseconds, leaseTime = 5.seconds)
        assertFailsWith<ExecutionException> {
            future.get()
        }
    }

    @Test
    fun `expired entry 는 다음 acquire 시 자동 회수된다`() {
        // 매우 짧은 leaseTime 으로 maxLeaders 만큼 잡고 release 호출 안 함 (crash simulation)
        val shortLeaseGroup = LettuceSlotTokenGroup(connection, "expire-test:${Base58.randomString(8)}", MAX_LEADERS)
        try {
            repeat(MAX_LEADERS) {
                val token = shortLeaseGroup.tryAcquire(waitTime = 500.milliseconds, leaseTime = 300.milliseconds)
                token.shouldNotBeNull()
                // intentional: release 호출 안 함
            }
            shortLeaseGroup.activeCount() shouldBeEqualTo MAX_LEADERS

            // leaseTime 만료 대기
            Thread.sleep(500)

            // 다음 acquire 가 expired entry 를 정리하고 성공해야 함
            val recovered = shortLeaseGroup.tryAcquire(waitTime = 500.milliseconds, leaseTime = 5.seconds)
            recovered.shouldNotBeNull()
            shortLeaseGroup.release(recovered, remainingMinLeaseMs = 0)
        } finally {
            connection.sync().del(shortLeaseGroup.slotKey)
        }
    }

    // ── Audit identity (T18 — meta hash HSET/HDEL atomicity) ─────────────────

    @Test
    fun `auditLeaderId acquire 시 meta hash 에 기록된다`() {
        val auditGroup = LettuceSlotTokenGroup(connection, "audit:${Base58.randomString(8)}", MAX_LEADERS)
        val token = auditGroup.tryAcquire(1.seconds, 10.seconds, auditLeaderId = "node-1")!!
        try {
            val stored = connection.sync().hget(auditGroup.metaKey, token)
            stored shouldBeEqualTo "node-1"
        } finally {
            auditGroup.release(token, remainingMinLeaseMs = 0)
            connection.sync().del(auditGroup.slotKey, auditGroup.metaKey)
        }
    }

    @Test
    fun `auditLeaderId release 시 meta hash 에서 삭제된다`() {
        val auditGroup = LettuceSlotTokenGroup(connection, "audit:${Base58.randomString(8)}", MAX_LEADERS)
        val token = auditGroup.tryAcquire(1.seconds, 10.seconds, auditLeaderId = "node-2")!!

        auditGroup.release(token, remainingMinLeaseMs = 0)

        val stored = connection.sync().hget(auditGroup.metaKey, token)
        stored.shouldBeNull()
        connection.sync().del(auditGroup.slotKey, auditGroup.metaKey)
    }

    @Test
    fun `auditLeaderId 빈 문자열이면 meta hash 에 기록되지 않는다`() {
        val auditGroup = LettuceSlotTokenGroup(connection, "audit:${Base58.randomString(8)}", MAX_LEADERS)
        val token = auditGroup.tryAcquire(1.seconds, 10.seconds, auditLeaderId = "")!!
        try {
            val stored = connection.sync().hget(auditGroup.metaKey, token)
            stored.shouldBeNull()
        } finally {
            auditGroup.release(token, remainingMinLeaseMs = 0)
            connection.sync().del(auditGroup.slotKey, auditGroup.metaKey)
        }
    }

    @Test
    fun `MultithreadingTester 3×100 — auditLeaderId concurrent HSET_HDEL 원자성 검증`() {
        val auditGroup = LettuceSlotTokenGroup(
            connection,
            "audit-concurrent:${Base58.randomString(8)}",
            MAX_LEADERS,
        )
        val successCount = AtomicInteger(0)

        MultithreadingTester()
            .workers(MAX_LEADERS)
            .rounds(100)
            .add {
                val leaderId = "node-${Thread.currentThread().threadId()}"
                val token = auditGroup.tryAcquire(2.seconds, 10.seconds, auditLeaderId = leaderId)
                if (token != null) {
                    // meta hash에 leaderId 기록 확인
                    val stored = connection.sync().hget(auditGroup.metaKey, token)
                    if (stored == leaderId) {
                        successCount.incrementAndGet()
                    }
                    auditGroup.release(token, remainingMinLeaseMs = 0)
                    // release 후 meta에서 삭제 확인
                    val afterRelease = connection.sync().hget(auditGroup.metaKey, token)
                    (afterRelease == null).shouldBeTrue()
                }
            }
            .run()

        // 3×100 중 최소 일부는 성공해야 함
        (successCount.get() > 0).shouldBeTrue()
        connection.sync().del(auditGroup.slotKey, auditGroup.metaKey)
    }
}

package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionException
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.LockAssert
import io.bluetape4k.leader.consul.internal.ConsulOwnerPayload
import io.bluetape4k.leader.consul.internal.JavaHttpConsulLockClient
import io.bluetape4k.testcontainers.infra.ConsulServer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulLeaderElectorIntegrationTest {

    private val consul: ConsulServer by lazy { ConsulServer.Launcher.consul }

    @Test
    fun `runIfLeader acquires releases and allows sequential reacquire`() {
        val elector = newElector()
        val lockName = randomName()

        elector.runIfLeader(lockName) {
            LockAssert.assertLocked(lockName)
            "first"
        } shouldBeEqualTo "first"

        elector.runIfLeader(lockName) { "second" } shouldBeEqualTo "second"
    }

    @Test
    fun `runIfLeader returns null on contention`() {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 200.milliseconds, leaseTime = 10.seconds),
        )
        val lockName = randomName()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val holderFuture = executor.submit<String?> {
                holder.runIfLeader(lockName) {
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    "holder"
                }
            }

            started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
            contender.runIfLeader(lockName) { "contender" }.shouldBeNull()

            release.countDown()
            holderFuture.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `state snapshot reports empty occupied and does not release holder`() {
        val keyPrefix = keyPrefix()
        val holder = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 2.seconds, leaseTime = 10.seconds, nodeId = "consul-state-node-a"),
        )
        val contender = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 200.milliseconds, leaseTime = 10.seconds),
        )
        val slot = LeaderSlot(lockName = randomName(), leaderId = "consul-state-audit-node-a")
        val empty = holder.state(slot.lockName)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        empty.lockName shouldBeEqualTo slot.lockName
        empty.isEmpty shouldBeEqualTo true
        empty.leader shouldBeEqualTo null

        try {
            val holderFuture = executor.submit<String?> {
                holder.runIfLeader(slot) {
                    val state = holder.state(slot.lockName)

                    state.lockName shouldBeEqualTo slot.lockName
                    state.isOccupied shouldBeEqualTo true
                    state.leader.shouldNotBeNull()
                    state.leader?.auditLeaderId shouldBeEqualTo "consul-state-audit-node-a"
                    state.leader?.nodeId shouldBeEqualTo "consul-state-node-a"
                    state.leader?.leaseUntil.shouldNotBeNull()
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    "holder"
                }
            }

            started.await(10, TimeUnit.SECONDS) shouldBeEqualTo true
            contender.runIfLeader(slot.lockName) { "contender" }.shouldBeNull()
            holder.state(slot.lockName).isOccupied shouldBeEqualTo true

            release.countDown()
            holderFuture.get(10, TimeUnit.SECONDS) shouldBeEqualTo "holder"
        } finally {
            release.countDown()
            executor.shutdownNow()
        }

        holder.state(slot.lockName).isEmpty shouldBeEqualTo true
        contender.runIfLeader(slot.lockName) { "takeover" } shouldBeEqualTo "takeover"
    }

    @Test
    fun `slot leader id is written as audit identity`() {
        val elector = newElector(
            leaderOptions = LeaderElectionOptions(
                waitTime = 1.seconds,
                leaseTime = 10.seconds,
                nodeId = "consul-node-a",
            ),
        )
        val slot = LeaderSlot(lockName = randomName(), leaderId = "audit-node-a")

        elector.runIfLeader(slot) {
            val state = elector.state(slot.lockName)

            state.leader.shouldNotBeNull()
            state.leader?.auditLeaderId shouldBeEqualTo "audit-node-a"
            state.leader?.nodeId shouldBeEqualTo "consul-node-a"
            "ok"
        } shouldBeEqualTo "ok"
    }

    @Test
    fun `action failure releases lease for next attempt`() {
        val elector = newElector()
        val lockName = randomName()

        assertFailsWith<LeaderElectionException> {
            elector.runIfLeader(lockName) {
                throw LeaderElectionException("action failed")
            }
        }

        elector.runIfLeader(lockName) { "recovered" } shouldBeEqualTo "recovered"
    }

    @Test
    fun `runAsyncIfLeader completes elected future`() {
        val elector = newElector()

        val result = elector.runAsyncIfLeader(randomName()) {
            CompletableFuture.completedFuture("async")
        }.get(10, TimeUnit.SECONDS)

        result shouldBeEqualTo "async"
    }

    @Test
    @Timeout(45)
    fun `expired session allows takeover`() {
        val keyPrefix = keyPrefix()
        val lockName = randomName()
        val client = JavaHttpConsulLockClient(endpoint(), keyPrefix)
        val sessionId = client.createSession("consul-expiry-test", 10.seconds, kotlin.time.Duration.ZERO)
            .get(10, TimeUnit.SECONDS)
        val key = client.singleLockKey(lockName)
        val now = Instant.now()
        val payload = ConsulOwnerPayload(
            ownerToken = "expired-owner",
            auditLeaderId = "expired-audit",
            nodeId = "expired-node",
            electedAt = now,
            leaseUntil = now.plusSeconds(10),
        )

        client.acquire(key, sessionId, payload.toJson()).get(10, TimeUnit.SECONDS) shouldBeEqualTo true

        val contender = newElector(
            keyPrefix = keyPrefix,
            leaderOptions = LeaderElectionOptions(waitTime = 30.seconds, leaseTime = 10.seconds),
        )

        contender.runIfLeader(lockName) { "takeover" } shouldBeEqualTo "takeover"
    }

    private fun newElector(
        keyPrefix: String = keyPrefix(),
        leaderOptions: LeaderElectionOptions = LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 10.seconds),
    ): ConsulLeaderElector =
        ConsulLeaderElector(
            endpoint(),
            ConsulLeaderElectionOptions(
                keyPrefix = keyPrefix,
                leaderOptions = leaderOptions,
            ),
        )

    private fun endpoint(): ConsulEndpoint =
        ConsulEndpoint(consul.url)

    private fun keyPrefix(): String =
        "bluetape4k/leader/test/${Base58.randomString(8)}"

    private fun randomName(): String =
        "leader-test:${Base58.randomString(8)}"
}

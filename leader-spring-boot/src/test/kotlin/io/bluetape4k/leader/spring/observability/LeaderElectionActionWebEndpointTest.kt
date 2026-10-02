package io.bluetape4k.leader.spring.observability

import io.bluetape4k.ToStringBuilder
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.LeaderLeaseHandle
import io.bluetape4k.leader.LeaderManagementActionObservation
import io.bluetape4k.leader.LeaderManagementActionOutcome
import io.bluetape4k.leader.LeaderManagementActionRegistry
import io.bluetape4k.leader.LeaderManagementActionSurface
import io.bluetape4k.leader.LeaseOwnershipStatus
import io.bluetape4k.leader.isManagementActionLockName
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class LeaderElectionActionWebEndpointTest {

    companion object: KLogging() {
        private val jsonMapper = Jackson.defaultJsonMapper
    }

    @Test
    fun `action endpoint is web-only and therefore not a JMX endpoint`() {
        LeaderElectionActionWebEndpoint::class.java
            .isAnnotationPresent(WebEndpoint::class.java)
            .shouldBeTrue()

        LeaderElectionActionWebEndpoint::class.java
            .isAnnotationPresent(Endpoint::class.java)
            .shouldBeFalse()
    }

    @Test
    fun `released action returns shared success status and the three-field response`() {
        val observations = mutableListOf<LeaderManagementActionObservation>()
        val registry = LeaderManagementActionRegistry(observer = { observations += it })
        val handle = FakeHandle("batch-job")
        registry.register(handle)

        val response = LeaderElectionActionWebEndpoint(registry).release("batch-job")

        response.status shouldBeEqualTo 200
        response.body.shouldNotBeNull() shouldBeEqualTo LeaderManagementActionHttpResponse(
            action = "RELEASE",
            outcome = "RELEASED",
            mutationAttempted = true,
        )
        log.debug { "handle=$handle" }
        handle.releaseCalls.get() shouldBeEqualTo 1

        log.debug { "observations=$observations" }
        observations.single().surface shouldBeEqualTo LeaderManagementActionSurface.SPRING
        registry.close()
    }

    @Test
    fun `unknown and invalid selectors never expose backend details`() {
        val registry = LeaderManagementActionRegistry()
        val endpoint = LeaderElectionActionWebEndpoint(registry)

        val unknown = endpoint.release("missing-job")
        val invalid = endpoint.release("bad/job")

        unknown.status shouldBeEqualTo 404
        invalid.status shouldBeEqualTo 400
        invalid.body.shouldNotBeNull().outcome shouldBeEqualTo LeaderManagementActionOutcome.INVALID_LOCK_NAME.name

        jsonMapper.writeValueAsString(invalid.body)
            .also { log.debug { "invalid.body=$it" } }
            .also { it shouldContain "\"action\":\"RELEASE\"" }
            .also { it shouldContain "\"outcome\":\"INVALID_LOCK_NAME\"" }

        setOf("lockName", "actor", "token", "exception")
            .forEach { forbidden ->
                jsonMapper.writeValueAsString(invalid.body) shouldNotContain forbidden
            }
        registry.close()
    }

    @Test
    fun `selector validator accepts only the management action grammar`() {
        "batch-job".isManagementActionLockName().shouldBeTrue()
        "bad/job".isManagementActionLockName().shouldBeFalse()
    }

    private class FakeHandle(
        override val lockName: String,
    ): LeaderLeaseHandle {
        private val ownershipCalls = AtomicInteger()
        val releaseCalls = AtomicInteger()

        override val auditLeaderId: String = "test-leader"
        override val acquiredAt: Instant = Instant.EPOCH

        override fun extend(lockAtMostFor: kotlin.time.Duration): ExtendOutcome = ExtendOutcome.NotHeld

        override fun ownershipStatus(): LeaseOwnershipStatus =
            if (ownershipCalls.getAndIncrement() == 0) LeaseOwnershipStatus.HELD else LeaseOwnershipStatus.NOT_HELD

        override fun isStillHeld(): Boolean = ownershipStatus() == LeaseOwnershipStatus.HELD

        override fun release() {
            releaseCalls.incrementAndGet()
        }

        override fun toString(): String {
            return ToStringBuilder(this)
                .add("lockName", lockName)
                .add("releaseCalls", releaseCalls.get())
                .add("auditLeaderId", auditLeaderId)
                .add("acquiredAt", acquiredAt)
                .toString()
        }
    }
}

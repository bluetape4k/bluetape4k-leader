package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotContainIgnoringCase
import io.bluetape4k.io.lookup
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LeaderManagementActionModelTest {

    companion object: KLogging()

    @Test
    fun `release action exposes the fixed outcome vocabulary`() {
        LeaderManagementAction.entries shouldBeEqualTo listOf(LeaderManagementAction.RELEASE)
        LeaderManagementActionOutcome.entries.map { it.name } shouldBeEqualTo listOf(
            "RELEASED",
            "INVALID_LOCK_NAME",
            "NOT_REGISTERED",
            "AMBIGUOUS",
            "NOT_HELD",
            "OWNERSHIP_UNKNOWN",
            "RELEASE_UNCONFIRMED",
            "RELEASE_FAILED",
            "REGISTRY_CLOSED",
            "ACTION_IN_PROGRESS",
            "ACTION_ADMISSION_REJECTED",
            "ACTION_TIMED_OUT",
        )
    }

    @Suppress("DEPRECATION")
    @Test
    fun `action result is serializable without internal payload`() {
        val original = LeaderManagementActionResult(
            action = LeaderManagementAction.RELEASE,
            outcome = LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
            mutationAttempted = true,
        )

        val bytes = BinarySerializers.JdkUnfiltered.serialize(original)
        val copy = BinarySerializers.JdkUnfiltered.deserialize<LeaderManagementActionResult>(bytes)

        copy shouldBeEqualTo original
        LeaderManagementActionResult::class.lookup().serialVersionUID shouldBeEqualTo 1L
        original.toString().let {
            it shouldNotContainIgnoringCase "exception"
            it shouldNotContainIgnoringCase "token"
        }
    }

    @Test
    fun `action result is serializable without internal payload for FastFory`() {
        val original = LeaderManagementActionResult(
            action = LeaderManagementAction.RELEASE,
            outcome = LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
            mutationAttempted = true,
        )

        val bytes = BinarySerializers.FastFory.serialize(original)
        val copy = BinarySerializers.FastFory.deserialize<LeaderManagementActionResult>(bytes)

        copy shouldBeEqualTo original
        LeaderManagementActionResult::class.lookup().serialVersionUID shouldBeEqualTo 1L

        original.toString().let {
            it shouldNotContainIgnoringCase "exception"
            it shouldNotContainIgnoringCase "token"
        }
    }

    @Test
    fun `registration close is idempotent and only invokes its callback once`() {
        val closeCount = AtomicInteger()
        val registration = LeaderManagementRegistration(
            accepted = true,
            outcome = LeaderManagementRegistrationOutcome.ACCEPTED,
            onClose = closeCount::incrementAndGet,
        )

        registration.accepted.shouldBeTrue()
        registration.outcome shouldBeEqualTo LeaderManagementRegistrationOutcome.ACCEPTED
        registration.close()
        registration.close()

        closeCount.get() shouldBeEqualTo 1
    }

    @Test
    fun `observer exposes only sanitized values`() {
        val observation = LeaderManagementActionObservation(
            surface = LeaderManagementActionSurface.CORE,
            outcome = LeaderManagementActionOutcome.RELEASED,
            phase = LeaderManagementActionPhase.TERMINALIZED,
            mutationAttempted = true,
            quarantined = false,
        )

        observation.quarantineReason.shouldBeNull()
        observation.toString().let {
            it shouldNotContainIgnoringCase "lock"
            it shouldNotContainIgnoringCase "token"
        }
    }

    @Test
    fun `http contract maps outcomes and disables automatic retry`() {
        LeaderManagementHttpContract.statusCode(LeaderManagementActionOutcome.RELEASED) shouldBeEqualTo 200
        LeaderManagementHttpContract.statusCode(LeaderManagementActionOutcome.INVALID_LOCK_NAME) shouldBeEqualTo 400
        LeaderManagementHttpContract.statusCode(LeaderManagementActionOutcome.NOT_REGISTERED) shouldBeEqualTo 404
        LeaderManagementHttpContract.statusCode(LeaderManagementActionOutcome.AMBIGUOUS) shouldBeEqualTo 409
        LeaderManagementHttpContract.statusCode(LeaderManagementActionOutcome.ACTION_ADMISSION_REJECTED) shouldBeEqualTo 429
        LeaderManagementHttpContract.statusCode(LeaderManagementActionOutcome.RELEASE_FAILED) shouldBeEqualTo 503
        LeaderManagementHttpContract.statusCode(LeaderManagementActionOutcome.ACTION_TIMED_OUT) shouldBeEqualTo 504
        LeaderManagementHttpContract.retryAllowed(LeaderManagementActionOutcome.RELEASED).shouldBeFalse()
        LeaderManagementHttpContract.retryAllowed(LeaderManagementActionOutcome.ACTION_TIMED_OUT).shouldBeFalse()
    }

    @Test
    fun `invalid registration outcome cannot be accepted`() {
        LeaderManagementRegistrationOutcome.entries
            .filter { it != LeaderManagementRegistrationOutcome.ACCEPTED }
            .forEach { outcome ->
                val registration = LeaderManagementRegistration(false, outcome)
                log.debug { "registration=$registration" }
                registration.accepted.shouldBeFalse()
            }

        assertFailsWith<IllegalArgumentException> {
            LeaderManagementRegistration(true, LeaderManagementRegistrationOutcome.INVALID_LOCK_NAME)
        }
    }
}

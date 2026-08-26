package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LeaderManagementActionModelTest {

    @Test
    fun `release action exposes the fixed outcome vocabulary`() {
        LeaderManagementAction.values().toList() shouldBeEqualTo listOf(LeaderManagementAction.RELEASE)
        LeaderManagementActionOutcome.values().map { it.name } shouldBeEqualTo listOf(
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

    @Test
    fun `action result is serializable without internal payload`() {
        val original = LeaderManagementActionResult(
            action = LeaderManagementAction.RELEASE,
            outcome = LeaderManagementActionOutcome.RELEASE_UNCONFIRMED,
            mutationAttempted = true,
        )

        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { stream -> stream.writeObject(original) }
            output.toByteArray()
        }
        val copy = ByteArrayInputStream(bytes).use { input ->
            ObjectInputStream(input).use { stream -> stream.readObject() as LeaderManagementActionResult }
        }

        copy shouldBeEqualTo original
        ObjectStreamClass.lookup(LeaderManagementActionResult::class.java).serialVersionUID shouldBeEqualTo 1L
        original.toString().contains("exception", ignoreCase = true).shouldBeFalse()
        original.toString().contains("token", ignoreCase = true).shouldBeFalse()
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

        observation.quarantineReason shouldBeEqualTo null
        observation.toString().contains("lock", ignoreCase = true).shouldBeFalse()
        observation.toString().contains("token", ignoreCase = true).shouldBeFalse()
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
        LeaderManagementRegistrationOutcome.values()
            .filter { it != LeaderManagementRegistrationOutcome.ACCEPTED }
            .forEach { outcome ->
                val registration = LeaderManagementRegistration(false, outcome)
                registration.accepted.shouldBeFalse()
            }
        assertFailsWith<IllegalArgumentException> {
            LeaderManagementRegistration(true, LeaderManagementRegistrationOutcome.INVALID_LOCK_NAME)
        }
    }
}

package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.strategy.CandidateInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.util.Base64
import org.junit.jupiter.api.Test

class LeaderSerializationCompatibilityTest {

    @Test
    fun `0_4_0 CandidateInfo payload fails closed after the explicit UID change`() {
        val exception = assertFailsWith<InvalidClassException> {
            ObjectInputStream(
                ByteArrayInputStream(Base64.getDecoder().decode(LEGACY_CANDIDATE_INFO)),
            ).use { input -> input.readObject() }
        }

        exception.message.orEmpty().contains("serialVersionUID").shouldBeTrue()
    }

    @Test
    fun `current serializable outcomes expose the explicit UID policy`() {
        ObjectStreamClass.lookup(CandidateInfo::class.java).serialVersionUID shouldBeEqualTo 1L
        ObjectStreamClass.lookup(ExtendOutcome.Extended::class.java).serialVersionUID shouldBeEqualTo 1L
        ObjectStreamClass.lookup(ExtendOutcome.Rejected::class.java).serialVersionUID shouldBeEqualTo 1L
        ObjectStreamClass.lookup(ExtendOutcome.NotHeld::class.java).serialVersionUID shouldBeEqualTo 1L
        ObjectStreamClass.lookup(ExtendOutcome.WrongThread::class.java).serialVersionUID shouldBeEqualTo 1L
        ObjectStreamClass.lookup(ExtendOutcome.BackendError::class.java).serialVersionUID shouldBeEqualTo 1L
    }

    @Test
    fun `current ExtendOutcome remains serializable`() {
        val original: ExtendOutcome = ExtendOutcome.WrongThread
        val bytes = ByteArrayOutputStream().use { output ->
            ObjectOutputStream(output).use { stream -> stream.writeObject(original) }
            output.toByteArray()
        }

        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readObject() as ExtendOutcome
        }
        restored shouldBeEqualTo original
    }

    companion object {
        private const val LEGACY_CANDIDATE_INFO =
            "rO0ABXNyACtpby5ibHVldGFwZTRrLmxlYWRlci5zdHJhdGVneS5DYW5kaWRhdGVJbmZvR4r+CyIiMA8CAAdKAAxmYWlsdXJlQ291bnRKAAxzdWNjZXNzQ291bnRMABJsYXN0Q29tcGxldGlvblRpbWV0ABNMamF2YS90aW1lL0luc3RhbnQ7TAANbGFzdFN0YXJ0VGltZXEAfgABTAAIbWV0YWRhdGF0AA9MamF2YS91dGlsL01hcDtMAAZub2RlSWR0ABJMamF2YS9sYW5nL1N0cmluZztMAAxyZWdpc3RlcmVkQXRxAH4AAXhwAAAAAAAAAAIAAAAAAAAAB3NyAA1qYXZhLnRpbWUuU2VylV2EuhsiSLIMAAB4cHcNAgAAAABpWdilAAAAAHhzcQB+AAV3DQIAAAAAaViHJQAAAAB4c3IAEWphdmEudXRpbC5Db2xsU2VyV46rtjobqBEDAAFJAAN0YWd4cAAAAAN3BAAAAAJ0AAR6b25ldAAGbGVnYWN5eHQAC2xlZ2FjeS1ub2Rlc3EAfgAFdw0CAAAAAGlXNaUAAAAAeA=="
    }
}

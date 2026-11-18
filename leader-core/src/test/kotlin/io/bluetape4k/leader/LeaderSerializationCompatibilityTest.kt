package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.io.lookup
import io.bluetape4k.io.serializer.BinarySerializationException
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.Serializable
import java.util.*

@Suppress("DEPRECATION")
class LeaderSerializationCompatibilityTest {

    companion object: KLogging() {
        private const val LEGACY_CANDIDATE_INFO =
            "rO0ABXNyACtpby5ibHVldGFwZTRrLmxlYWRlci5zdHJhdGVneS5DYW5kaWRhdGVJbmZvR4r+CyIiMA8CAAdKAAxmYWlsdXJlQ291bnRKAAxzdWNjZXNzQ291bnRMABJsYXN0Q29tcGxldGlvblRpbWV0ABNMamF2YS90aW1lL0luc3RhbnQ7TAANbGFzdFN0YXJ0VGltZXEAfgABTAAIbWV0YWRhdGF0AA9MamF2YS91dGlsL01hcDtMAAZub2RlSWR0ABJMamF2YS9sYW5nL1N0cmluZztMAAxyZWdpc3RlcmVkQXRxAH4AAXhwAAAAAAAAAAIAAAAAAAAAB3NyAA1qYXZhLnRpbWUuU2VylV2EuhsiSLIMAAB4cHcNAgAAAABpWdilAAAAAHhzcQB+AAV3DQIAAAAAaViHJQAAAAB4c3IAEWphdmEudXRpbC5Db2xsU2VyV46rtjobqBEDAAFJAAN0YWd4cAAAAAN3BAAAAAJ0AAR6b25ldAAGbGVnYWN5eHQAC2xlZ2FjeS1ub2Rlc3EAfgAFdw0CAAAAAGlXNaUAAAAAeA=="
    }

    @Test
    fun `0_4_0 CandidateInfo payload fails closed after the explicit UID change`() {
        val exception = assertFailsWith<InvalidClassException> {
            ObjectInputStream(
                ByteArrayInputStream(Base64.getDecoder().decode(LEGACY_CANDIDATE_INFO)),
            ).use { input -> input.readObject() }
        }

        exception.message shouldContain "serialVersionUID"
    }

    @Test
    fun `current serializable outcomes expose the explicit UID policy`() {
        CandidateInfo::class.lookup().serialVersionUID shouldBeEqualTo 1L
        ExtendOutcome.Extended::class.lookup().serialVersionUID shouldBeEqualTo 1L
        ExtendOutcome.Rejected::class.lookup().serialVersionUID shouldBeEqualTo 1L
        ExtendOutcome.NotHeld::class.lookup().serialVersionUID shouldBeEqualTo 1L
        ExtendOutcome.WrongThread::class.lookup().serialVersionUID shouldBeEqualTo 1L
        ExtendOutcome.BackendError::class.lookup().serialVersionUID shouldBeEqualTo 1L
    }

    @Suppress("DEPRECATION")
    @Test
    fun `current ExtendOutcome remains serializable`() {
        val original: ExtendOutcome = ExtendOutcome.WrongThread
        val bytes = BinarySerializers.JdkUnfiltered.serialize(original)
        val restored = BinarySerializers.JdkUnfiltered.deserialize<ExtendOutcome>(bytes)

        log.debug { "restored=$restored" }
        restored shouldBeEqualTo original
    }

    @Test
    fun `current ExtendOutcome remains serializable for FastFory`() {
        val original: ExtendOutcome = ExtendOutcome.WrongThread
        val bytes = BinarySerializers.FastFory.serialize(original)
        val restored = BinarySerializers.FastFory.deserialize<ExtendOutcome>(bytes)
        restored shouldBeEqualTo original
    }

    @Test
    fun `current options remain serializable after validation`() {
        listOf<Any>(LeaderElectionOptions.Default, LeaderGroupElectionOptions.Default).forEach { original ->
            deserialize(serialize(original)) shouldBeEqualTo original
        }
    }

    @Test
    fun `LeaderElectionOptions rejects invalid serialized invariants`() {
        assertInvalidSerializedFields(
            LeaderElectionOptions.Default,
            listOf(
                "waitTime" to -1_000_000L,
                "leaseTime" to 0L,
                "nodeId" to " ",
                "minLeaseTime" to -1_000_000L,
                "minLeaseTime" to Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `LeaderGroupElectionOptions rejects invalid serialized invariants`() {
        assertInvalidSerializedFields(
            LeaderGroupElectionOptions.Default,
            listOf(
                "maxLeaders" to 0,
                "waitTime" to -1_000_000L,
                "leaseTime" to 0L,
                "nodeId" to " ",
                "minLeaseTime" to -1_000_000L,
                "minLeaseTime" to Long.MAX_VALUE,
            ),
        )
    }

    private fun deserialize(bytes: ByteArray): Serializable =
        BinarySerializers.JdkUnfiltered.deserialize(bytes)!!

    private fun serialize(value: Any): ByteArray =
        BinarySerializers.JdkUnfiltered.serialize(value)


    private fun assertInvalidSerializedFields(value: Any, replacements: List<Pair<String, Any>>) {
        replacements.forEach { (fieldName, replacement) ->
            try {
                assertFailsWith<BinarySerializationException> {
                    deserialize(serializeWithField(value, fieldName, replacement))
                }
            } catch (cause: AssertionError) {
                throw AssertionError("Expected invalid serialized field: $fieldName", cause)
            }
        }
    }

    private fun serializeWithField(value: Any, fieldName: String, replacement: Any): ByteArray {
        val field = value.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
        val original = field.get(value)

        return try {
            field.set(value, replacement)
            serialize(value)
        } finally {
            field.set(value, original)
        }
    }
}

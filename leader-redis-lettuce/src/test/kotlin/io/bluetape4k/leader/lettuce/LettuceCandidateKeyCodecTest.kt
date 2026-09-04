package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.validateLockName
import io.lettuce.core.cluster.SlotHash
import org.junit.jupiter.api.Test

class LettuceCandidateKeyCodecTest {

    @Test
    fun `v3 index and candidate keys use the same hash slot`() {
        val prefix = "leader:strategy:candidates"
        val lockName = "결정:lock"
        val nodeId = "node:1"

        val index = LettuceCandidateKeyCodec.indexKey(prefix, lockName)
        val candidate = LettuceCandidateKeyCodec.candidateKey(prefix, lockName, nodeId)

        index shouldBeEqualTo "leader:strategy:candidates|v3|i|{11:결정:lock}"
        candidate shouldBeEqualTo "leader:strategy:candidates|v3|c|{11:결정:lock}6:node:1"
        SlotHash.getSlot(index) shouldBeEqualTo SlotHash.getSlot(candidate)
    }

    @Test
    fun `v3 lifecycle keys share the candidate hash slot`() {
        val prefix = "leader:strategy:candidates"
        val lockName = "lock"
        val nodeId = "node"
        val keys = listOf(
            LettuceCandidateKeyCodec.indexKey(prefix, lockName),
            LettuceCandidateKeyCodec.candidateKey(prefix, lockName, nodeId),
            LettuceCandidateKeyCodec.tombstoneKey(prefix, lockName, nodeId),
            LettuceCandidateKeyCodec.migrationTokenKey(prefix, lockName, nodeId),
        )

        keys.map(SlotHash::getSlot).distinct().size shouldBeEqualTo 1
    }

    @Test
    fun `legacy source keys retain their original layout`() {
        val prefix = "leader:strategy:candidates"
        val lockName = "legacy:lock"
        val nodeId = "legacy:node"

        LettuceCandidateKeyCodec.legacyIndexKey(prefix, lockName) shouldBeEqualTo
            "leader:strategy:candidates:legacy:lock"
        LettuceCandidateKeyCodec.legacyCandidateKey(prefix, lockName, nodeId) shouldBeEqualTo
            "leader:strategy:candidates:legacy:lock:legacy:node"
    }

    @Test
    fun `lock names containing hash tag braces remain invalid`() {
        assertFailsWith<IllegalArgumentException> { validateLockName("a{b") }
        assertFailsWith<IllegalArgumentException> { validateLockName("a}b") }
    }
}

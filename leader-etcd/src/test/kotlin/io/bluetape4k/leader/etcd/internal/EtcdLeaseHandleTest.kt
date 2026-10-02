package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.etcd.support.toByteSequence
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.etcd.jetcd.ByteSequence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaseHandleTest {

    companion object: KLogging()

    @Test
    fun `ownership token is deterministic hex for arbitrary ownership key bytes`() {
        val bytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val key = bytes.toByteSequence()

        EtcdLeaseHandle.ownershipToken(key) shouldBeEqualTo "007f80ff"
        EtcdLeaseHandle.ownershipToken(key) shouldBeEqualTo EtcdLeaseHandle.ownershipToken(key)
    }

    @Test
    fun `handle validates lease id lock name and ownership key`() {
        val key = ByteSequence.from(byteArrayOf(1))

        assertFailsWith<IllegalArgumentException> { EtcdLeaseHandle(0L, "job", key) }
        assertFailsWith<IllegalArgumentException> { EtcdLeaseHandle(1L, "../job", key) }
        assertFailsWith<IllegalArgumentException> { EtcdLeaseHandle(1L, "job", ByteSequence.EMPTY) }
    }

    @Test
    fun `mark released is idempotent`() {
        val handle = EtcdLeaseHandle(1L, "job", byteArrayOf(1).toByteSequence())

        log.debug { "handle=$handle" }
        handle.isReleased.shouldBeFalse()
        handle.markReleased().shouldBeTrue()
        handle.isReleased.shouldBeTrue()
        handle.markReleased().shouldBeFalse()
    }
}

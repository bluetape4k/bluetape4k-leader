package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdLeaderPathsTest {

    @Test
    fun `single leader path uses default prefix and encoded lock name`() {
        EtcdLeaderPaths().single("batch:daily") shouldBeEqualTo
            "/bluetape4k/leader/single/batch%3Adaily"
    }

    @Test
    fun `group slot path uses zero based slot suffix`() {
        EtcdLeaderPaths("/service/leader/").groupSlot("batch_job", 2) shouldBeEqualTo
            "/service/leader/group/batch_job/slot-2"
    }

    @Test
    fun `lock name validation runs before path construction`() {
        val paths = EtcdLeaderPaths()

        assertFailsWith<IllegalArgumentException> { paths.single("../job") }
        assertFailsWith<IllegalArgumentException> { paths.groupSlot("job", -1) }
    }

    @Test
    fun `prefix must be absolute and non blank`() {
        assertFailsWith<IllegalArgumentException> { EtcdLeaderPaths("") }
        assertFailsWith<IllegalArgumentException> { EtcdLeaderPaths("/") }
        assertFailsWith<IllegalArgumentException> { EtcdLeaderPaths("relative") }
    }
}

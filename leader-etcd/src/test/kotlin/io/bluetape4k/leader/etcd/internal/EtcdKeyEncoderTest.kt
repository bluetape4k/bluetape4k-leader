package io.bluetape4k.leader.etcd.internal

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.charset.CharacterCodingException

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdKeyEncoderTest {

    @Test
    fun `safe ASCII path segment bytes remain unchanged`() {
        EtcdKeyEncoder.encodeSegment("Az09._-") shouldBeEqualTo "Az09._-"
    }

    @Test
    fun `slash and colon are percent encoded to avoid path injection`() {
        EtcdKeyEncoder.encodeSegment("group/job:slot") shouldBeEqualTo "group%2Fjob%3Aslot"
    }

    @Test
    fun `unicode lock names round trip through UTF-8 percent encoding`() {
        val value = "배치-작업"
        val encoded = EtcdKeyEncoder.encodeSegment(value)

        encoded shouldNotContain "/"
        EtcdKeyEncoder.decodeSegment(encoded) shouldBeEqualTo value
    }

    @Test
    fun `adversarial path payloads do not survive as raw path separators`() {
        val payloads = listOf("../job", "/leading", "a//b", "a\u0000b", "a%2Fb")

        payloads.forEach { payload ->
            val encoded = EtcdKeyEncoder.encodeSegment(payload)
            encoded shouldNotContain "/"
            EtcdKeyEncoder.decodeSegment(encoded) shouldBeEqualTo payload
        }
    }

    @Test
    fun `different raw names keep distinct encoded segments`() {
        EtcdKeyEncoder.encodeSegment("a/b") shouldBeEqualTo "a%2Fb"
        EtcdKeyEncoder.encodeSegment("a%2Fb") shouldBeEqualTo "a%252Fb"
    }

    @Test
    fun `malformed percent encoding is rejected`() {
        assertFailsWith<IllegalArgumentException> { EtcdKeyEncoder.decodeSegment("%") }
        assertFailsWith<IllegalArgumentException> { EtcdKeyEncoder.decodeSegment("%GG") }
        assertFailsWith<CharacterCodingException> { EtcdKeyEncoder.decodeSegment("%FF") }
    }
}

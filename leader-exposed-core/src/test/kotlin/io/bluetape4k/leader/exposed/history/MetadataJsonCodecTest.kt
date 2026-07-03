package io.bluetape4k.leader.exposed.history

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test

class MetadataJsonCodecTest {

    companion object : KLogging()

    @Test
    fun `encode empty map returns null`() {
        MetadataJsonCodec.encode(emptyMap()).shouldBeNull()
    }

    @Test
    fun `encode and decode round-trips a simple map`() {
        val map = mapOf("env" to "prod", "region" to "us-east-1")
        val json = MetadataJsonCodec.encode(map).shouldNotBeNull()
        val decoded = MetadataJsonCodec.decode(json)
        decoded shouldBeEqualTo map
    }

    @Test
    fun `encode escapes double-quotes and backslashes`() {
        val map = mapOf("msg" to """say "hello" \n world""")
        val json = MetadataJsonCodec.encode(map).shouldNotBeNull()
        val decoded = MetadataJsonCodec.decode(json)
        decoded shouldBeEqualTo map
    }

    @Test
    fun `decode null returns empty map`() {
        MetadataJsonCodec.decode(null).shouldBeEmpty()
    }

    @Test
    fun `decode blank string returns empty map`() {
        MetadataJsonCodec.decode("   ").shouldBeEmpty()
    }

    @Test
    fun `decode empty braces returns empty map`() {
        MetadataJsonCodec.decode("{}").shouldBeEmpty()
    }

    @Test
    fun `decode single entry map`() {
        val json = """{"key":"value"}"""
        MetadataJsonCodec.decode(json) shouldBeEqualTo mapOf("key" to "value")
    }

    @Test
    fun `encode preserves insertion order via LinkedHashMap`() {
        val map = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val json = MetadataJsonCodec.encode(map).shouldNotBeNull()
        val decoded = MetadataJsonCodec.decode(json)
        decoded.keys.toList() shouldBeEqualTo listOf("a", "b", "c")
    }

    @Test
    fun `encode and decode map with special characters in values`() {
        val map = mapOf("url" to "https://example.com/path?a=1&b=2")
        val json = MetadataJsonCodec.encode(map).shouldNotBeNull()
        MetadataJsonCodec.decode(json) shouldBeEqualTo map
    }
}

package io.bluetape4k.leader.exposed.history

import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataJsonCodecTest {

    companion object : KLogging()

    @Test
    fun `encode empty map returns null`() {
        assertNull(MetadataJsonCodec.encode(emptyMap()))
    }

    @Test
    fun `encode and decode round-trips a simple map`() {
        val map = mapOf("env" to "prod", "region" to "us-east-1")
        val json = MetadataJsonCodec.encode(map)!!
        val decoded = MetadataJsonCodec.decode(json)
        assertEquals(map, decoded)
    }

    @Test
    fun `encode escapes double-quotes and backslashes`() {
        val map = mapOf("msg" to """say "hello" \n world""")
        val json = MetadataJsonCodec.encode(map)!!
        val decoded = MetadataJsonCodec.decode(json)
        assertEquals(map, decoded)
    }

    @Test
    fun `decode null returns empty map`() {
        assertTrue(MetadataJsonCodec.decode(null).isEmpty())
    }

    @Test
    fun `decode blank string returns empty map`() {
        assertTrue(MetadataJsonCodec.decode("   ").isEmpty())
    }

    @Test
    fun `decode empty braces returns empty map`() {
        assertTrue(MetadataJsonCodec.decode("{}").isEmpty())
    }

    @Test
    fun `decode single entry map`() {
        val json = """{"key":"value"}"""
        assertEquals(mapOf("key" to "value"), MetadataJsonCodec.decode(json))
    }

    @Test
    fun `encode preserves insertion order via LinkedHashMap`() {
        val map = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val json = MetadataJsonCodec.encode(map)!!
        val decoded = MetadataJsonCodec.decode(json)
        assertEquals(listOf("a", "b", "c"), decoded.keys.toList())
    }

    @Test
    fun `encode and decode map with special characters in values`() {
        val map = mapOf("url" to "https://example.com/path?a=1&b=2")
        val json = MetadataJsonCodec.encode(map)!!
        assertEquals(map, MetadataJsonCodec.decode(json))
    }
}

package io.bluetape4k.leader.mongodb.history

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MongoHistoryConfigTest {

    @Test
    fun `history collection name rejects reserved MongoDB namespaces`() {
        assertFailsWith<IllegalArgumentException> {
            MongoHistoryConfig(collectionName = "system.profile")
        }
        assertFailsWith<IllegalArgumentException> {
            MongoHistoryConfig(collectionName = "leader\$history")
        }
        assertFailsWith<IllegalArgumentException> {
            MongoHistoryConfig(collectionName = "")
        }
    }

    @Test
    fun `history TTL must be non-negative`() {
        assertFailsWith<IllegalArgumentException> {
            MongoHistoryConfig(ttlDays = -1)
        }
    }
}

package io.bluetape4k.leader.dynamodb

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DynamoDbLeaderOptionsValidationTest {

    @Test
    fun `single leader options validate table namespace before client calls`() {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbLeaderElectionOptions(tableName = "ab")
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbLeaderElectionOptions(tableName = "bad/table")
        }
    }

    @Test
    fun `group leader options validate key prefix before slot key composition`() {
        assertFailsWith<IllegalArgumentException> {
            DynamoDbLeaderGroupElectionOptions(keyPrefix = "/tenant")
        }
        assertFailsWith<IllegalArgumentException> {
            DynamoDbLeaderGroupElectionOptions(keyPrefix = "tenant#slot-0")
        }
    }
}

package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.idgenerators.uuid.Uuid
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.UUID
import org.junit.jupiter.api.Test

class DynamoDbLockClientOwnerIdTest {

    @Test
    fun `new owner id delegates to bluetape4k UUID v4 generator`() {
        // Given
        val expected = UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
        mockkObject(Uuid.V4)

        try {
            every { Uuid.V4.nextUUID() } returns expected

            // When
            val ownerId = DynamoDbLockClient.newOwnerId()

            // Then
            ownerId shouldBeEqualTo expected.toString()
            verify(exactly = 1) { Uuid.V4.nextUUID() }
        } finally {
            unmockkObject(Uuid.V4)
        }
    }

    @Test
    fun `new owner ids preserve canonical UUID v4 format and remain unique`() {
        // Given
        val sampleSize = 256

        // When
        val ownerIds = List(sampleSize) { DynamoDbLockClient.newOwnerId() }

        // Then
        ownerIds shouldHaveSize sampleSize
        ownerIds.toSet() shouldHaveSize sampleSize
        ownerIds.map(UUID::fromString).forEach { ownerId ->
            ownerId.version() shouldBeEqualTo 4
        }
    }
}

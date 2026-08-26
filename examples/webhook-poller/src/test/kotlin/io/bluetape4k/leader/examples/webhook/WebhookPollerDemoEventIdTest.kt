package io.bluetape4k.leader.examples.webhook

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.idgenerators.uuid.Uuid
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.UUID
import org.junit.jupiter.api.Test

class WebhookPollerDemoEventIdTest {

    @Test
    fun `demo event id delegates to bluetape4k UUID v4 generator`() {
        // Given
        val expected = UUID.fromString("123e4567-e89b-42d3-a456-426614174000")
        mockkObject(Uuid.V4)

        try {
            every { Uuid.V4.nextUUID() } returns expected

            // When
            val eventId = WebhookPollerDemo.newEventId(index = 1)

            // Then
            eventId shouldBeEqualTo "evt-1-${expected}"
            verify(exactly = 1) { Uuid.V4.nextUUID() }
        } finally {
            unmockkObject(Uuid.V4)
        }
    }

    @Test
    fun `reruns produce unique canonical UUID v4 event ids`() {
        // Given
        val eventsPerRun = 256

        // When
        val firstRun = List(eventsPerRun) { WebhookPollerDemo.newEventId(index = 1) }
        val secondRun = List(eventsPerRun) { WebhookPollerDemo.newEventId(index = 1) }
        val allEventIds = firstRun + secondRun

        // Then
        allEventIds shouldHaveSize eventsPerRun * 2
        allEventIds.toSet() shouldHaveSize eventsPerRun * 2
        allEventIds.forEach { eventId ->
            eventId.startsWith("evt-1-") shouldBeEqualTo true
            val uuidText = eventId.removePrefix("evt-1-")
            val parsed = UUID.fromString(uuidText)
            uuidText shouldBeEqualTo parsed.toString()
            parsed.version() shouldBeEqualTo 4
        }
    }
}

package io.bluetape4k.leader.spring.properties

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.junit.jupiter.api.Test
import java.time.Duration

class LeaderRouteRedirectPropertiesTest {

    companion object: KLogging()

    @Test
    fun `default redirect policy is disabled and empty`() {
        val properties = LeaderRouteRedirectProperties()

        log.debug { "properties=$properties" }
        properties.enabled.shouldBeFalse()
        properties.allowedHosts.shouldBeEmpty()
        properties.trustedProxyAddresses.shouldBeEmpty()
        properties.leaseSafetyWindow shouldBeEqualTo Duration.ZERO
    }

    @Test
    fun `enabled policy rejects wildcard host and proxy syntax`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteRedirectProperties(enabled = true, allowedHosts = listOf("*.example")).normalized()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteRedirectProperties(enabled = true, trustedProxyAddresses = listOf("10.0.0.0/8")).normalized()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteRedirectProperties(enabled = true, allowedHosts = listOf("127.0.0.01")).normalized()
        }
        assertFailsWith<IllegalArgumentException> {
            LeaderRouteRedirectProperties(enabled = true, allowedHosts = listOf("leader..example")).normalized()
        }
    }
}

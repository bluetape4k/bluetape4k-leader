package io.bluetape4k.leader.spring.route

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.net.URI

class LeaderRouteRedirectUriValidatorTest {

    @Test
    fun `relative path is safe but network path and fragment are rejected`() {
        LeaderRouteRedirectUriValidator.isSafe(URI("/leader/orders"), setOf("leader.example")).shouldBeTrue()
        LeaderRouteRedirectUriValidator.isSafe(URI("//evil.example/orders"), setOf("leader.example")).shouldBeFalse()
        LeaderRouteRedirectUriValidator.isSafe(URI("/leader/orders#fragment"), setOf("leader.example")).shouldBeFalse()
    }

    @Test
    fun `absolute target requires HTTPS exact host and implicit default port`() {
        LeaderRouteRedirectUriValidator.isSafe(
            URI("https://leader.example/orders"),
            setOf("leader.example"),
        ).shouldBeTrue()
        listOf(
            URI("http://leader.example/orders"),
            URI("https://leader.example:443/orders"),
            URI("https://leader.example:8443/orders"),
            URI("https://leader.example:/orders"),
            URI("https://user@leader.example/orders"),
            URI("https://leader.example/orders#fragment"),
            URI("https://other.example/orders"),
            URI("https://leader.example/%0d%0aX-Test:%20bad"),
            URI("https://leader.example/%5c%5cprivate"),
            URI("https://127.0.0.01/orders"),
            URI("https://[::ffff:127.0.0.1]/orders"),
        ).forEach { uri ->
            LeaderRouteRedirectUriValidator.isSafe(uri, setOf("leader.example")).shouldBeFalse()
        }

        LeaderRouteRedirectUriValidator.isSafe(
            URI("https://127.0.0.01/orders"),
            setOf("127.0.0.01"),
        ).shouldBeFalse()
    }
}

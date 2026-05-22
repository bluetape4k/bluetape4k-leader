package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulEndpointTest {

    @Test
    fun `accepts http endpoint and normalizes trailing slash`() {
        val endpoint = ConsulEndpoint(
            baseUrl = "http://localhost:8500/",
            datacenter = "dc1",
            aclToken = "token",
            requestTimeout = 3.seconds,
        )

        endpoint.normalizedBaseUrl.toString() shouldBeEqualTo "http://localhost:8500"
        endpoint.datacenter shouldBeEqualTo "dc1"
        endpoint.aclToken shouldBeEqualTo "token"
        endpoint.requestTimeout shouldBeEqualTo 3.seconds
    }

    @Test
    fun `rejects unsupported endpoint shapes`() {
        assertFailsWith<IllegalArgumentException> {
            ConsulEndpoint("ftp://localhost:8500")
        }
        assertFailsWith<IllegalArgumentException> {
            ConsulEndpoint("http://user:password@localhost:8500")
        }
        assertFailsWith<IllegalArgumentException> {
            ConsulEndpoint("http://localhost:8500?token=secret")
        }
        assertFailsWith<IllegalArgumentException> {
            ConsulEndpoint("http://localhost:8500", requestTimeout = 0.seconds)
        }
    }

    @Test
    fun `masks acl token in string form`() {
        val endpoint = ConsulEndpoint("http://localhost:8500", aclToken = "secret-token")

        endpoint.toString() shouldBeEqualTo
            "ConsulEndpoint(baseUrl=http://localhost:8500, datacenter=null, aclToken=***, requestTimeout=5s)"
    }
}

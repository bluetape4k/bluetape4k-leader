package io.bluetape4k.leader.spring.route.mvc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteRedirectProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteRejectionStatus
import io.bluetape4k.leader.spring.route.LeaderRouteAuthority
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.NullLeaderRouteAuthority
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadata
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectRequestMetadataProvider
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectResolver
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.servlet.http.HttpServletRequest
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class LeaderMvcRouteGuardTest {

    private val slot = LeaderSlot("orders-route", "node-a")
    private val invocations = AtomicInteger()
    private val controller = TestController(invocations)

    @BeforeEach
    fun resetInvocations() {
        invocations.set(0)
    }

    @Test
    fun `allowed request invokes handler exactly once`() {
        mockMvc(LeaderRouteAuthority { LeaderRouteDecision.Allowed })
            .get("/guarded")
            .andExpect {
                status { isOk() }
                content { string("served") }
            }

        invocations.get() shouldBeEqualTo 1
    }

    @Test
    fun `route-scoped registration leaves unguarded route unaffected`() {
        val interceptor = factory(LeaderRouteAuthority { LeaderRouteDecision.NotLeader }).interceptor(slot)
        val mockMvc = MockMvcBuilders
            .standaloneSetup(controller)
            .addMappedInterceptors(arrayOf("/guarded"), interceptor)
            .build()

        mockMvc.get("/unguarded")
            .andExpect {
                status { isOk() }
                content { string("served") }
            }

        invocations.get() shouldBeEqualTo 1
    }

    @ParameterizedTest
    @EnumSource(LeaderRouteRejectionStatus::class)
    fun `rejected request returns configured status and empty sanitized response`(
        rejectionStatus: LeaderRouteRejectionStatus,
    ) {
        mockMvc(LeaderRouteAuthority { LeaderRouteDecision.NotLeader }, rejectionStatus)
            .get("/guarded")
            .andExpect {
                status { isEqualTo(rejectionStatus.value) }
                content { string("") }
                header { doesNotExist("Location") }
                header { doesNotExist("X-Leader-Id") }
            }

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `unavailable and authority failure reject before handler invocation`() {
        listOf(
            LeaderRouteAuthority { LeaderRouteDecision.Unavailable },
            LeaderRouteAuthority { error("backend password=secret") },
        ).forEach { authority ->
            mockMvc(authority)
                .get("/guarded")
                .andExpect {
                    status { isServiceUnavailable() }
                    content { string("") }
                }
        }

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `resolver-only relative target returns temporary redirect without invoking handler`() {
        factory(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(enabled = true),
        ).interceptor(slot, LeaderRouteRedirectResolver { java.net.URI("/leader/orders") })
            .let { interceptor ->
                MockMvcBuilders
                    .standaloneSetup(controller)
                    .addInterceptors(interceptor)
                    .build()
                    .get("/guarded")
                    .andExpect {
                        status { isTemporaryRedirect() }
                        header { string("Location", "/leader/orders") }
                    }
            }

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `resolver-only absolute target is rejected without location`() {
        factory(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(enabled = true, allowedHosts = listOf("leader.example")),
        ).interceptor(slot, LeaderRouteRedirectResolver { java.net.URI("https://leader.example/orders") })
            .let { interceptor ->
                MockMvcBuilders
                    .standaloneSetup(controller)
                    .addInterceptors(interceptor)
                    .build()
                    .get("/guarded")
                    .andExpect {
                        status { isServiceUnavailable() }
                        header { doesNotExist("Location") }
                    }
            }
    }

    @Test
    fun `trusted raw metadata permits exact absolute target`() {
        val metadataProvider = LeaderRouteRedirectRequestMetadataProvider<HttpServletRequest> {
            LeaderRouteRedirectRequestMetadata(true, "10.0.0.10")
        }
        factory(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(
                enabled = true,
                allowedHosts = listOf("leader.example"),
                trustedProxyAddresses = listOf("10.0.0.10"),
            ),
        ).interceptor(
            slot,
            LeaderRouteRedirectResolver { java.net.URI("https://leader.example/orders") },
            metadataProvider,
        ).let { interceptor ->
            MockMvcBuilders
                .standaloneSetup(controller)
                .addInterceptors(interceptor)
                .build()
                .get("/guarded")
                .andExpect {
                    status { isTemporaryRedirect() }
                    header { string("Location", "https://leader.example/orders") }
                }
        }
    }

    @Test
    fun `redirect disabled never calls resolver or metadata provider`() {
        val resolverCalls = AtomicInteger()
        val providerCalls = AtomicInteger()
        val interceptor = factory(LeaderRouteAuthority { LeaderRouteDecision.NotLeader })
            .interceptor(
                slot,
                LeaderRouteRedirectResolver {
                    resolverCalls.incrementAndGet()
                    java.net.URI("/leader/orders")
                },
                LeaderRouteRedirectRequestMetadataProvider {
                    providerCalls.incrementAndGet()
                    LeaderRouteRedirectRequestMetadata(false, null)
                },
            )

        MockMvcBuilders
            .standaloneSetup(controller)
            .addInterceptors(interceptor)
            .build()
            .get("/guarded")
            .andExpect { status { isServiceUnavailable() } }

        resolverCalls.get() shouldBeEqualTo 0
        providerCalls.get() shouldBeEqualTo 0
    }

    @Test
    fun `null Java authority result fails closed with configured status`() {
        mockMvc(NullLeaderRouteAuthority(), LeaderRouteRejectionStatus.LOCKED)
            .get("/guarded")
            .andExpect {
                status { isLocked() }
                content { string("") }
            }

        invocations.get() shouldBeEqualTo 0
    }

    @Test
    fun `runtime metadata provider exception rejects without location`() {
        val interceptor = factory(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(enabled = true),
        ).interceptor(
            slot,
            LeaderRouteRedirectResolver { java.net.URI("/leader/orders") },
            LeaderRouteRedirectRequestMetadataProvider { throw RuntimeException("metadata unavailable") },
        )

        MockMvcBuilders
            .standaloneSetup(controller)
            .addInterceptors(interceptor)
            .build()
            .get("/guarded")
            .andExpect {
                status { isServiceUnavailable() }
                header { doesNotExist("Location") }
            }
    }

    @Test
    fun `checked metadata provider exception rejects without location`() {
        val interceptor = factory(
            LeaderRouteAuthority { LeaderRouteDecision.NotLeader },
            redirect = LeaderRouteRedirectProperties(enabled = true),
        ).interceptor(
            slot,
            LeaderRouteRedirectResolver { java.net.URI("/leader/orders") },
            LeaderRouteRedirectRequestMetadataProvider { throw Exception("metadata unavailable") },
        )

        MockMvcBuilders
            .standaloneSetup(controller)
            .addInterceptors(interceptor)
            .build()
            .get("/guarded")
            .andExpect {
                status { isServiceUnavailable() }
                header { doesNotExist("Location") }
            }
    }

    @Test
    fun `cancellation from authority is preserved`() {
        val interceptor = factory(LeaderRouteAuthority { throw CancellationException("cancelled") }).interceptor(slot)

        assertFailsWith<CancellationException> {
            interceptor.preHandle(mockk(), mockk(relaxed = true), Any())
        }
    }

    @Test
    fun `interruption from authority is preserved and restores interrupt flag`() {
        val interceptor = factory(LeaderRouteAuthority { throw InterruptedException("interrupted") }).interceptor(slot)

        try {
            assertFailsWith<InterruptedException> {
                interceptor.preHandle(mockk(), mockk(relaxed = true), Any())
            }
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
    }

    private fun mockMvc(
        authority: LeaderRouteAuthority,
        rejectionStatus: LeaderRouteRejectionStatus = LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE,
    ) = MockMvcBuilders
        .standaloneSetup(controller)
        .addInterceptors(factory(authority, rejectionStatus).interceptor(slot))
        .build()

    private fun factory(
        authority: LeaderRouteAuthority,
        rejectionStatus: LeaderRouteRejectionStatus = LeaderRouteRejectionStatus.SERVICE_UNAVAILABLE,
        redirect: LeaderRouteRedirectProperties = LeaderRouteRedirectProperties(),
    ): LeaderMvcRouteGuardFactory =
        LeaderMvcRouteGuardFactory(
            runtime = LeaderRouteAuthorityRuntime(authority),
            properties = LeaderRouteGuardProperties(rejectionStatus = rejectionStatus, redirect = redirect),
        )

    @RestController
    private class TestController(
        private val invocations: AtomicInteger,
    ) {
        @GetMapping("/guarded", produces = [MediaType.TEXT_PLAIN_VALUE])
        fun guarded(): String {
            invocations.incrementAndGet()
            return "served"
        }

        @GetMapping("/unguarded", produces = [MediaType.TEXT_PLAIN_VALUE])
        fun unguarded(): String {
            invocations.incrementAndGet()
            return "served"
        }
    }
}

package io.bluetape4k.leader.spring.route.mvc

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.properties.LeaderRouteRejectionStatus
import io.bluetape4k.leader.spring.route.LeaderRouteAuthority
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.NullLeaderRouteAuthority
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
    ): LeaderMvcRouteGuardFactory =
        LeaderMvcRouteGuardFactory(
            runtime = LeaderRouteAuthorityRuntime(authority),
            properties = LeaderRouteGuardProperties(rejectionStatus = rejectionStatus),
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

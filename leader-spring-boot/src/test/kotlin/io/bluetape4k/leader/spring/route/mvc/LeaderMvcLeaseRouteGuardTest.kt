package io.bluetape4k.leader.spring.route.mvc

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.spring.properties.LeaderRouteAuthorityMode
import io.bluetape4k.leader.spring.properties.LeaderRouteGuardProperties
import io.bluetape4k.leader.spring.route.LeaderRouteAuthority
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteDecision
import io.bluetape4k.leader.spring.route.LeaderRouteLeaseRuntime
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LeaderMvcLeaseRouteGuardTest {

    private val slot = LeaderSlot("orders-route", "request-node")

    @Test
    fun `lease is held for request completion and released cross thread`() {
        val elector = LocalLeaderElector(
            LeaderElectionOptions(
                waitTime = 20.milliseconds,
                leaseTime = 1.seconds,
                nodeId = "baseline-node",
            ),
        )
        val factory = factory(elector)
        val interceptor = factory.interceptor(slot)
        val firstRequest = MockHttpServletRequest()
        val firstResponse = MockHttpServletResponse()

        interceptor.preHandle(firstRequest, firstResponse, Any()).shouldBeTrue()

        val contenderResponse = MockHttpServletResponse()
        interceptor.preHandle(MockHttpServletRequest(), contenderResponse, Any()).shouldBeFalse()
        contenderResponse.status shouldBeEqualTo 503

        val completed = CountDownLatch(1)
        val completionThread = Thread {
            interceptor.afterCompletion(firstRequest, firstResponse, Any(), null)
            completed.countDown()
        }
        completionThread.start()
        completed.await(2, TimeUnit.SECONDS).shouldBeTrue()
        completionThread.join()
        awaitLeaseRelease(elector)

        val nextResponse = MockHttpServletResponse()
        interceptor.preHandle(MockHttpServletRequest(), nextResponse, Any()).shouldBeTrue()
    }

    @Test
    fun `lease contention does not invoke the handler or expose a marker`() {
        val elector = LocalLeaderElector(
            LeaderElectionOptions(waitTime = 1.milliseconds, leaseTime = 1.seconds, nodeId = "baseline"),
        )
        val factory = factory(elector)
        val interceptor = factory.interceptor(slot)
        val firstRequest = MockHttpServletRequest()
        val firstResponse = MockHttpServletResponse()
        interceptor.preHandle(firstRequest, firstResponse, Any()).shouldBeTrue()

        val contenderRequest = MockHttpServletRequest()
        val contenderResponse = MockHttpServletResponse()
        interceptor.preHandle(contenderRequest, contenderResponse, Any()).shouldBeFalse()
        contenderRequest.getAttribute("io.bluetape4k.leader.spring.route.mvc.LEASE_HANDLE") shouldBeEqualTo null

        interceptor.afterCompletion(firstRequest, firstResponse, Any(), null)
    }

    @Test
    fun `stale fingerprint rejects after releasing the previously published lease`() {
        val elector = LocalLeaderElector(
            LeaderElectionOptions(waitTime = 10.milliseconds, leaseTime = 1.seconds, nodeId = "baseline"),
        )
        val factory = factory(elector)
        val firstInterceptor = factory.interceptor(slot)
        val staleInterceptor = factory.interceptor(LeaderSlot("other-route", "request-node"))
        val request = MockHttpServletRequest()

        firstInterceptor.preHandle(request, MockHttpServletResponse(), Any()).shouldBeTrue()
        staleInterceptor.preHandle(request, MockHttpServletResponse(), Any()).shouldBeFalse()
        awaitLeaseRelease(elector)

        val nextResponse = MockHttpServletResponse()
        firstInterceptor.preHandle(MockHttpServletRequest(), nextResponse, Any()).shouldBeTrue()
        firstInterceptor.afterCompletion(request, MockHttpServletResponse(), Any(), null)
    }

    private fun factory(elector: LocalLeaderElector): LeaderMvcRouteGuardFactory {
        val properties = LeaderRouteGuardProperties(
            enabled = true,
            authorityMode = LeaderRouteAuthorityMode.LEASE,
        )
        return LeaderMvcRouteGuardFactory(
            runtime = LeaderRouteAuthorityRuntime(LeaderRouteAuthority { LeaderRouteDecision.Allowed }),
            properties = properties,
            redirectPolicy = null,
            leaseRuntime = LeaderRouteLeaseRuntime(elector, null, properties.lease),
        )
    }

    private fun awaitLeaseRelease(elector: LocalLeaderElector) {
        val deadline = System.nanoTime() + 1.seconds.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            val probe = elector.tryAcquire(slot)
            if (probe != null) {
                probe.release()
                return
            }
            Thread.sleep(5)
        }
        error("lease cleanup did not complete within the test deadline")
    }
}

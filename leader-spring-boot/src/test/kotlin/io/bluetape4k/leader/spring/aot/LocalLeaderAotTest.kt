package io.bluetape4k.leader.spring.aot

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.bluetape4k.leader.spring.backend.LocalLeaderConfiguration
import io.bluetape4k.leader.spring.route.LeaderRouteAuthorityRuntime
import io.bluetape4k.leader.spring.route.LeaderRouteGuardAutoConfiguration
import io.bluetape4k.leader.spring.route.LeaderRouteRedirectPolicy
import io.bluetape4k.leader.spring.route.StateLeaderRouteAuthority
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * Validates that leader-spring-boot auto-configurations are AOT-compatible.
 *
 * Runs with `-Dspring.aot.enabled=true` (Spring AOT mode). Uses only [LocalLeaderElector]
 * — no Redis, MongoDB, or Hazelcast required — so this test is safe for CI without Docker.
 *
 * Included by the `aotTest` Gradle task via `processTestAot` filtered to the `aot` package.
 *
 * ## Local command
 * ```bash
 * ./gradlew :leader-spring-boot:aotTest
 * ```
 */
@SpringBootTest(
    classes = [LeaderTestApplication::class],
    properties = [
        "bluetape4k.leader.route-guard.enabled=true",
        "bluetape4k.leader.route-guard.redirect.enabled=true",
        "bluetape4k.leader.route-guard.redirect.allowed-hosts[0]=leader.example",
    ],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ImportAutoConfiguration(
    LeaderElectionAutoConfiguration::class,
    LocalLeaderConfiguration::class,
    LeaderRouteGuardAutoConfiguration::class,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalLeaderAotTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var leaderElector: LeaderElector

    @Autowired
    private lateinit var suspendLeaderElector: SuspendLeaderElector

    @Autowired
    private lateinit var routeAuthorityRuntime: LeaderRouteAuthorityRuntime

    @Autowired
    private lateinit var redirectPolicy: LeaderRouteRedirectPolicy

    @Test
    fun `ApplicationContext loads with local backend in AOT mode`() {
        context.shouldNotBeNull()
    }

    @Test
    fun `LocalLeaderElector bean type is correct in AOT mode`() {
        leaderElector.shouldBeInstanceOf<LocalLeaderElector>()
    }

    @Test
    fun `LocalSuspendLeaderElector bean type is correct in AOT mode`() {
        suspendLeaderElector.shouldBeInstanceOf<LocalSuspendLeaderElector>()
    }

    @Test
    fun `state route authority runtime is created in AOT mode`() {
        routeAuthorityRuntime.authority.shouldBeInstanceOf<StateLeaderRouteAuthority>()
    }

    @Test
    fun `redirect policy bean is created in AOT mode`() {
        redirectPolicy.shouldNotBeNull()
        context.getBeansOfType(LeaderRouteRedirectPolicy::class.java).size shouldBeEqualTo 1
    }

    @Test
    fun `runIfLeader returns action value in AOT mode`() {
        val lockName = "aot-test-${Base58.randomString(6)}"
        val result = leaderElector.runIfLeader(lockName) { "aot-ok" }
        result shouldBeEqualTo "aot-ok"
    }

    @Test
    fun `suspend runIfLeader returns action value in AOT mode`() {
        val lockName = "aot-suspend-${Base58.randomString(6)}"
        val result = runBlocking { suspendLeaderElector.runIfLeader(lockName) { "aot-suspend-ok" } }
        result shouldBeEqualTo "aot-suspend-ok"
    }
}

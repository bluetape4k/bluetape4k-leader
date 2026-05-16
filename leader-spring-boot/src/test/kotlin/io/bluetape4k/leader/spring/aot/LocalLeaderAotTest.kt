package io.bluetape4k.leader.spring.aot

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.spring.LeaderElectionAutoConfiguration
import io.bluetape4k.leader.spring.LeaderTestApplication
import io.bluetape4k.leader.spring.backend.LocalLeaderConfiguration
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.assertions.shouldNotBeNull
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
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@ImportAutoConfiguration(
    LeaderElectionAutoConfiguration::class,
    LocalLeaderConfiguration::class,
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalLeaderAotTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var leaderElector: LeaderElector

    @Autowired
    private lateinit var suspendLeaderElector: SuspendLeaderElector

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

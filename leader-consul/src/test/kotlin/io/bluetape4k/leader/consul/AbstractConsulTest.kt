package io.bluetape4k.leader.consul

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderLease
import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.infra.ConsulServer
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractConsulTest {

    companion object: KLogging() {

        @JvmStatic
        protected val consul: ConsulServer by lazy { ConsulServer.Launcher.consul }

        @JvmStatic
        protected fun endpoint(): ConsulEndpoint = ConsulEndpoint(consul.url)

        @JvmStatic
        protected fun keyPrefix(): String =
            "bluetape4k/leader/test/${Base58.randomString(8).lowercase()}"

        @JvmStatic
        protected fun randomName(): String =
            "leader-group-test:${Base58.randomString(8).lowercase()}"

    }

    protected infix fun List<LeaderLease>.shouldHaveSingleLeader(auditLeaderId: String) {
        singleOrNull().shouldNotBeNull()
        single().auditLeaderId shouldBeEqualTo auditLeaderId
    }
}

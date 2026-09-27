package io.bluetape4k.leader.consul.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderGroupElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Direct Consul executor overload coverage for single and group slot paths.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulExecutorOverloadContractTest {

    companion object: KLogging()

    @Test
    fun singleExecutorOverloadPropagatesLeaderIdAndReleases() {
        val elector = ConsulLeaderElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderElectionOptions(keyPrefix = ConsulContractSupport.keyPrefix()),
        )
        val lockName = "consul-executor-single-contract"

        val first = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "consul-single-a"),
            VirtualThreadExecutor,
        ) {
            completableFutureOf("single-ok")
        }.join()

        first.shouldBeInstanceOf<LeaderRunResult.Elected<*>>()
        first.value shouldBeEqualTo "single-ok"
        first.leaderId shouldBeEqualTo "consul-single-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "consul-single-b"),
            VirtualThreadExecutor,
        ) {
            completableFutureOf("single-reacquired")
        }.join()

        second.shouldBeInstanceOf<LeaderRunResult.Elected<*>>()
        second.value shouldBeEqualTo "single-reacquired"
        second.leaderId shouldBeEqualTo "consul-single-b"
    }

    @Test
    fun groupExecutorOverloadPropagatesLeaderIdAndReleases() {
        val elector = ConsulLeaderGroupElector(
            ConsulContractSupport.endpoint(),
            ConsulLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                keyPrefix = ConsulContractSupport.keyPrefix(),
            ),
        )
        val lockName = "consul-executor-group-contract"

        val first = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "consul-group-a"),
            VirtualThreadExecutor,
        ) {
            completableFutureOf("group-ok")
        }.join()

        first.shouldBeInstanceOf<LeaderRunResult.Elected<*>>()
        first.value shouldBeEqualTo "group-ok"
        first.leaderId shouldBeEqualTo "consul-group-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "consul-group-b"),
            VirtualThreadExecutor,
        ) {
            completableFutureOf("group-reacquired")
        }.join()

        second.shouldBeInstanceOf<LeaderRunResult.Elected<*>>()
        second.value shouldBeEqualTo "group-reacquired"
        second.leaderId shouldBeEqualTo "consul-group-b"
    }
}

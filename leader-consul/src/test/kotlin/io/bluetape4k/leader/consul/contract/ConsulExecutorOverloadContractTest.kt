package io.bluetape4k.leader.consul.contract

import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.consul.ConsulLeaderElector
import io.bluetape4k.leader.consul.ConsulLeaderElectionOptions
import io.bluetape4k.leader.consul.ConsulLeaderGroupElector
import io.bluetape4k.leader.consul.ConsulLeaderGroupElectionOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture

/**
 * Direct Consul executor overload coverage for single and group slot paths.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsulExecutorOverloadContractTest {

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
            CompletableFuture.completedFuture("single-ok")
        }.join()

        first shouldBeInstanceOf LeaderRunResult.Elected::class
        (first as LeaderRunResult.Elected).value shouldBeEqualTo "single-ok"
        first.leaderId shouldBeEqualTo "consul-single-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "consul-single-b"),
            VirtualThreadExecutor,
        ) {
            CompletableFuture.completedFuture("single-reacquired")
        }.join()

        second shouldBeInstanceOf LeaderRunResult.Elected::class
        (second as LeaderRunResult.Elected).value shouldBeEqualTo "single-reacquired"
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
            CompletableFuture.completedFuture("group-ok")
        }.join()

        first shouldBeInstanceOf LeaderRunResult.Elected::class
        (first as LeaderRunResult.Elected).value shouldBeEqualTo "group-ok"
        first.leaderId shouldBeEqualTo "consul-group-a"

        val second = elector.runAsyncIfLeaderResult(
            LeaderSlot(lockName, "consul-group-b"),
            VirtualThreadExecutor,
        ) {
            CompletableFuture.completedFuture("group-reacquired")
        }.join()

        second shouldBeInstanceOf LeaderRunResult.Elected::class
        (second as LeaderRunResult.Elected).value shouldBeEqualTo "group-reacquired"
        second.leaderId shouldBeEqualTo "consul-group-b"
    }
}

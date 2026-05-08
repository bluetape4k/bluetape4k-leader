package io.bluetape4k.leader.zookeeper

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class ZooKeeperApiCoverageTest: AbstractZooKeeperLeaderTest() {

    @Test
    fun `CuratorFramework extension functions execute leader actions`() = runTest {
        val lockName = randomName()
        val groupName = randomName()
        val groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 5.seconds)

        curator.runIfLeader(lockName) { "blocking" } shouldBeEqualTo "blocking"
        curator.runAsyncIfLeader("${lockName}-async") {
            CompletableFuture.completedFuture("async")
        }.join() shouldBeEqualTo "async"

        curator.runIfLeaderGroup(groupName, groupOptions) { "group" } shouldBeEqualTo "group"
        curator.runAsyncIfLeaderGroup("${groupName}-async", options = groupOptions) {
            CompletableFuture.completedFuture("group-async")
        }.join() shouldBeEqualTo "group-async"

        curator.suspendRunIfLeader("${lockName}-suspend") { "suspend" } shouldBeEqualTo "suspend"
        curator.suspendRunIfLeaderGroup("${groupName}-suspend", groupOptions) {
            "group-suspend"
        } shouldBeEqualTo "group-suspend"
    }

    @Test
    fun `factories create electors that execute actions`() = runTest {
        val singleOptions = LeaderElectionOptions(waitTime = 5.seconds, leaseTime = 30.seconds)
        val groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 5.seconds)

        ZooKeeperLeaderElectorFactory(curator)
            .create(singleOptions)
            .runIfLeader(randomName()) { "factory" } shouldBeEqualTo "factory"

        ZooKeeperLeaderGroupElectorFactory(curator)
            .create(groupOptions)
            .runIfLeader(randomName()) { "group-factory" } shouldBeEqualTo "group-factory"

        ZooKeeperSuspendLeaderElectorFactory(curator)
            .create(singleOptions)
            .runIfLeader(randomName()) { "suspend-factory" } shouldBeEqualTo "suspend-factory"

        ZooKeeperSuspendLeaderGroupElectorFactory(curator)
            .create(groupOptions)
            .runIfLeader(randomName()) { "suspend-group-factory" } shouldBeEqualTo "suspend-group-factory"
    }

    @Test
    fun `group state reports active count and available slots`() {
        val lockName = randomName()
        val options = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 5.seconds)
        val election = ZooKeeperLeaderGroupElector(curator, options)

        election.activeCount(lockName) shouldBeEqualTo 0
        election.availableSlots(lockName) shouldBeEqualTo 2
        election.state(lockName).availableSlots shouldBeEqualTo 2

        election.runIfLeader(lockName) {
            election.activeCount(lockName) shouldBeEqualTo 1
            election.availableSlots(lockName) shouldBeEqualTo 1
            election.state(lockName).activeCount shouldBeEqualTo 1
        }

        election.activeCount(lockName) shouldBeEqualTo 0
    }

    @Test
    fun `root base path is normalized`() {
        val result = curator.runIfLeader(randomName(), basePath = "/") { "root" }

        result shouldBeEqualTo "root"
    }
}

package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class ZooKeeperApiCoverageTest: AbstractZooKeeperLeaderTest() {

    @Test
    fun `CuratorFramework extension functions execute leader actions`() = runTest {
        val lockName = randomName()
        val groupName = randomName()
        val groupOptions = LeaderGroupElectionOptions(maxLeaders = 2, waitTime = 5.seconds)

        curator.runIfLeader(lockName) { "blocking" } shouldBeEqualTo "blocking"
        curator.runIfLeader(ZooKeeperElectionPath.single("${lockName}-typed")) {
            "blocking-typed"
        } shouldBeEqualTo "blocking-typed"

        curator.runAsyncIfLeader("${lockName}-async") {
            CompletableFuture.completedFuture("async")
        }.join() shouldBeEqualTo "async"
        curator.runAsyncIfLeader(ZooKeeperElectionPath.single("${lockName}-async-typed")) {
            CompletableFuture.completedFuture("async-typed")
        }.join() shouldBeEqualTo "async-typed"

        curator.runIfLeaderGroup(groupName, groupOptions) { "group" } shouldBeEqualTo "group"
        curator.runIfLeaderGroup(ZooKeeperElectionPath.group("${groupName}-typed"), groupOptions) {
            "group-typed"
        } shouldBeEqualTo "group-typed"

        curator.runAsyncIfLeaderGroup("${groupName}-async", options = groupOptions) {
            CompletableFuture.completedFuture("group-async")
        }.join() shouldBeEqualTo "group-async"
        curator.runAsyncIfLeaderGroup(ZooKeeperElectionPath.group("${groupName}-async-typed"), options = groupOptions) {
            CompletableFuture.completedFuture("group-async-typed")
        }.join() shouldBeEqualTo "group-async-typed"

        curator.suspendRunIfLeader("${lockName}-suspend") { "suspend" } shouldBeEqualTo "suspend"
        curator.suspendRunIfLeader(ZooKeeperElectionPath.single("${lockName}-suspend-typed")) {
            "suspend-typed"
        } shouldBeEqualTo "suspend-typed"

        curator.suspendRunIfLeaderGroup("${groupName}-suspend", groupOptions) {
            "group-suspend"
        } shouldBeEqualTo "group-suspend"
        curator.suspendRunIfLeaderGroup(ZooKeeperElectionPath.group("${groupName}-suspend-typed"), groupOptions) {
            "group-suspend-typed"
        } shouldBeEqualTo "group-suspend-typed"
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

        val suspendElector = ZooKeeperSuspendLeaderElectorFactory(curator).create(singleOptions)
        try {
            suspendElector.runIfLeader(randomName()) { "suspend-factory" } shouldBeEqualTo "suspend-factory"
        } finally {
            suspendElector.close()
        }

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

    @Test
    fun `ZooKeeper path construction rejects unsafe lock name segments`() {
        listOf("a/b", "../escape", "a//b", ".", "..", " lock").forEach { lockName ->
            assertFailsWith<IllegalArgumentException> {
                ZooKeeperPaths.electionPath("/leader-election", lockName)
            }
            assertFailsWith<IllegalArgumentException> {
                ZooKeeperElectionPath.single(lockName)
            }
        }
    }

    @Test
    fun `ZooKeeper path construction validates and normalizes base path`() {
        ZooKeeperPaths.electionPath("/leader-election/", "daily-job") shouldBeEqualTo "/leader-election/daily-job"
        ZooKeeperPaths.electionPath("/", "daily-job") shouldBeEqualTo "/daily-job"

        listOf("leader-election", "/leader-election//nested", "/leader-election/../escape").forEach { basePath ->
            assertFailsWith<IllegalArgumentException> {
                ZooKeeperPaths.electionPath(basePath, "daily-job")
            }
            assertFailsWith<IllegalArgumentException> {
                ZooKeeperElectionPath.single("daily-job", basePath)
            }
        }
    }

    @Test
    fun `suspend elector reuses owner dispatcher across runIfLeader calls`() = runTest {
        val election = ZooKeeperSuspendLeaderElector(curator)
        try {
            repeat(3) { index ->
                election.runIfLeader("${randomName()}-$index") { "ok" } shouldBeEqualTo "ok"
            }
        } finally {
            election.close()
        }

        val source = Path.of(
            "src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperSuspendLeaderElector.kt",
        ).toFile().readText()
        val runStart = source.indexOf("private suspend fun <T> runWithOwnerDispatcher")
        val closeStart = source.indexOf("override fun close()", startIndex = runStart)
        val runBody = source.substring(runStart, closeStart)

        source.contains("private class ZooKeeperOwnerDispatcherPool").shouldBeTrue()
        (!runBody.contains("Executors.newSingleThreadExecutor")).shouldBeTrue()
    }
}

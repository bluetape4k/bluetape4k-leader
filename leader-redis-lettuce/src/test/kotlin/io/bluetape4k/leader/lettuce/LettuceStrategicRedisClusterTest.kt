@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.lettuce.script.RedisScript
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.strategies.FifoElectionStrategy
import io.bluetape4k.leader.strategy.strategies.FifoGroupElectionStrategy
import io.bluetape4k.testcontainers.storage.RedisClusterServer
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.coroutines
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Tag("redis-cluster")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceStrategicRedisClusterTest {

    private val clusterImage = checkNotNull(javaClass.getResource("/redis-cluster-image.txt"))
        .readText().trim()
    // digest를 지정할 수 없는 전역 launcher 대신 기존 서버 factory를 재사용하고 이 테스트가 수명을 소유한다.
    private val server = RedisClusterServer(DockerImageName.parse(clusterImage))

    @BeforeAll
    fun startCluster() {
        try {
            server.start()
        } catch (failure: Throwable) {
            try {
                server.stop()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    @AfterAll
    fun stopCluster() {
        server.stop()
    }

    @Test
    fun `blocking single elector executes v3 register list refresh result and unregister`() {
        withCluster { connection ->
            val lockName = "cluster-single-${System.nanoTime()}"
            val nodeId = "cluster-node-1"
            val elector = LettuceStrategicLeaderElector(connection, nodeId)

            elector.registerCandidate(lockName, CandidateInfo(nodeId))
            elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
            elector.refreshCandidate(lockName, CandidateInfo(nodeId, metadata = mapOf("path" to "cluster")))
            elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            elector.listCandidates(lockName).single().successCount shouldBeEqualTo 1L
            elector.runIfLeader(lockName, FifoElectionStrategy) { "done" } shouldBeEqualTo "done"

            elector.unregisterCandidate(lockName, nodeId)
            elector.listCandidates(lockName).shouldBeEmpty()
            connection.sync().get(
                LettuceCandidateKeyCodec.tombstoneKey(
                    LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                    lockName,
                    nodeId,
                ),
            ).shouldNotBeNull()
        }
    }

    @Test
    fun `blocking group elector uses cluster mget and same-slot scripts`() {
        withCluster { connection ->
            val lockName = "cluster-group-${System.nanoTime()}"
            val electors = listOf("cluster-group-1", "cluster-group-2", "cluster-group-3")
                .map { LettuceStrategicLeaderGroupElector(connection, it) }
            electors.forEach { elector ->
                elector.registerCandidate(lockName, CandidateInfo(elector.nodeId))
            }

            electors.flatMap { it.listCandidates(lockName) }.map(CandidateInfo::nodeId).distinct().size shouldBeEqualTo 3
            electors[0].runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) { "winner" }
                .shouldBeEqualTo("winner")
            electors[2].runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 2) {
                error("탈락한 후보의 action은 실행되면 안 됨")
            }.shouldBeNull()
        }
    }

    @Test
    fun `suspend single elector preserves cancellation and cluster lifecycle`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-${System.nanoTime()}"
            val nodeId = "cluster-suspend-node"
            val elector = LettuceStrategicSuspendLeaderElector(connection, nodeId)
            elector.registerCandidate(lockName, CandidateInfo(nodeId))

            elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
            elector.refreshCandidate(lockName, CandidateInfo(nodeId, metadata = mapOf("path" to "suspend")))
            elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            elector.listCandidates(lockName).single().successCount shouldBeEqualTo 1L

            val cancellation = CancellationException("cluster action cancelled")
            val thrown = assertFailsWith<CancellationException> {
                elector.runIfLeader(lockName, FifoElectionStrategy) { throw cancellation }
            }
            thrown.message shouldBeEqualTo cancellation.message
            elector.listCandidates(lockName).single().failureCount shouldBeEqualTo 0L
        }
    }

    @Test
    fun `suspend group elector uses cluster coroutine mget and group script`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-group-${System.nanoTime()}"
            val electors = listOf("cluster-suspend-group-1", "cluster-suspend-group-2")
                .map { LettuceStrategicSuspendLeaderGroupElector(connection, it) }
            electors.forEach { elector ->
                elector.registerCandidate(lockName, CandidateInfo(elector.nodeId))
            }

            electors.first().listCandidates(lockName).size shouldBeEqualTo 2
            electors.first().runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 1) { "winner" }
                .shouldBeEqualTo("winner")
        }
    }

    @Test
    fun `v2 and colon sources migrate through single-key reads and remain preserved`() {
        withCluster { connection ->
            val lockName = "cluster-migration-${System.nanoTime()}"
            val v2NodeId = "cluster-migration-v2"
            val v2Raw = LettuceCandidateInfoCodec.encode(
                CandidateInfo(v2NodeId, metadata = mapOf("source" to "v2")),
            )
            val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                v2NodeId,
            )
            val v2Index = LettuceCandidateKeyCodec.v2IndexKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
            )
            val currentIndex = LettuceCandidateKeyCodec.indexKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
            )
            val currentKey = LettuceCandidateKeyCodec.candidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                v2NodeId,
            )
            val mismatchedCurrentRaw = LettuceCandidateInfoCodec.encode(
                CandidateInfo("cluster-migration-v3-other", metadata = mapOf("source" to "wrong-v3")),
            )
            val colonNodeId = "cluster-migration-colon"
            val colonRaw = LettuceCandidateInfoCodec.encode(
                CandidateInfo(colonNodeId, metadata = mapOf("source" to "colon")),
            )
            val colonIndex = "${LettuceCandidateRegistry.DEFAULT_KEY_PREFIX}:$lockName"
            val colonKey = "$colonIndex:$colonNodeId"
            connection.sync().set(currentKey, mismatchedCurrentRaw)
            connection.sync().sadd(currentIndex, v2NodeId)
            connection.sync().set(v2Key, v2Raw)
            connection.sync().sadd(v2Index, v2NodeId)
            connection.sync().set(colonKey, colonRaw)
            connection.sync().sadd(colonIndex, colonNodeId)

            val listed = LettuceStrategicLeaderElector(connection, "observer").listCandidates(lockName)
            listed.map { it.nodeId }.toSet() shouldBeEqualTo setOf(v2NodeId, colonNodeId)
            listed.first { it.nodeId == v2NodeId }.metadata shouldBeEqualTo mapOf("source" to "v2")
            listed.first { it.nodeId == colonNodeId }.metadata shouldBeEqualTo mapOf("source" to "colon")
            connection.sync().get(v2Key) shouldBeEqualTo v2Raw
            connection.sync().get(colonKey) shouldBeEqualTo colonRaw
            connection.sync().get(currentKey) shouldBeEqualTo v2Raw
            connection.sync().sismember(currentIndex, v2NodeId) shouldBeEqualTo true
            connection.sync().get(
                LettuceCandidateKeyCodec.candidateKey(
                    LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                    lockName,
                    colonNodeId,
                ),
            ) shouldBeEqualTo colonRaw
        }
    }

    @Test
    fun `cluster migration preserves positive source TTL on the v3 destination`() {
        withCluster { connection ->
            val lockName = "cluster-migration-ttl-${System.nanoTime()}"
            val nodeId = "cluster-migration-ttl-node"
            val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
            val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            )
            val v2Index = LettuceCandidateKeyCodec.v2IndexKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
            )
            connection.sync().set(v2Key, raw, SetArgs.Builder.px(3_000))
            connection.sync().sadd(v2Index, nodeId)

            val sourceTtl = connection.sync().pttl(v2Key)
            sourceTtl shouldBeGreaterThan 0L
            LettuceStrategicLeaderElector(connection, "observer")
                .listCandidates(lockName)
                .single()
                .nodeId shouldBeEqualTo nodeId

            val destinationKey = LettuceCandidateKeyCodec.candidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            )
            val destinationTtl = connection.sync().pttl(destinationKey)
            destinationTtl shouldBeGreaterThan 0L
            destinationTtl shouldBeLessOrEqualTo sourceTtl
            connection.sync().get(v2Key) shouldBeEqualTo raw
        }
    }

    @Test
    fun `blocking group elector refreshes result and unregisters in cluster`() {
        withCluster { connection ->
            val lockName = "cluster-group-lifecycle-${System.nanoTime()}"
            val nodeId = "cluster-group-lifecycle-node"
            val elector = LettuceStrategicLeaderGroupElector(connection, nodeId)

            elector.registerCandidate(lockName, CandidateInfo(nodeId))
            elector.refreshCandidate(
                lockName,
                CandidateInfo(nodeId, metadata = mapOf("phase" to "refreshed")),
                2.seconds,
            )
            elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)

            val listed = elector.listCandidates(lockName).single()
            listed.metadata shouldBeEqualTo mapOf("phase" to "refreshed")
            listed.successCount shouldBeEqualTo 1L
            elector.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 1) { "winner" }
                .shouldBeEqualTo("winner")

            elector.unregisterCandidate(lockName, nodeId)
            elector.listCandidates(lockName).shouldBeEmpty()
        }
    }

    @Test
    fun `blocking group elector migrates a v2 source before refreshing and unregistering`() {
        withCluster { connection ->
            val lockName = "cluster-group-migration-${System.nanoTime()}"
            val nodeId = "cluster-group-migration-node"
            val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
            val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
                LettuceCandidateRegistry.GROUP_KEY_PREFIX,
                lockName,
                nodeId,
            )
            val v2Index = LettuceCandidateKeyCodec.v2IndexKey(
                LettuceCandidateRegistry.GROUP_KEY_PREFIX,
                lockName,
            )
            connection.sync().set(v2Key, raw, SetArgs.Builder.px(3_000))
            connection.sync().sadd(v2Index, nodeId)

            val elector = LettuceStrategicLeaderGroupElector(connection, nodeId)
            elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
            elector.refreshCandidate(
                lockName,
                CandidateInfo(nodeId, metadata = mapOf("source" to "v2-refresh")),
                2.seconds,
            )
            elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            elector.listCandidates(lockName).single().successCount shouldBeEqualTo 1L

            elector.unregisterCandidate(lockName, nodeId)
            elector.listCandidates(lockName).shouldBeEmpty()
            connection.sync().get(v2Key).shouldBeNull()
        }
    }

    @Test
    fun `suspend single elector migrates a v2 source before refreshing and unregistering`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-migration-${System.nanoTime()}"
            val nodeId = "cluster-suspend-migration-node"
            val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
            val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            )
            val v2Index = LettuceCandidateKeyCodec.v2IndexKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
            )
            connection.sync().set(v2Key, raw, SetArgs.Builder.px(3_000))
            connection.sync().sadd(v2Index, nodeId)

            val elector = LettuceStrategicSuspendLeaderElector(connection, nodeId)
            elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
            elector.refreshCandidate(
                lockName,
                CandidateInfo(nodeId, metadata = mapOf("source" to "v2-refresh")),
                2.seconds,
            )
            elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            elector.listCandidates(lockName).single().successCount shouldBeEqualTo 1L

            elector.unregisterCandidate(lockName, nodeId)
            elector.listCandidates(lockName).shouldBeEmpty()
            connection.sync().get(v2Key).shouldBeNull()
        }
    }

    @Test
    fun `suspend group elector refreshes result and unregisters in cluster`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-group-lifecycle-${System.nanoTime()}"
            val nodeId = "cluster-suspend-group-lifecycle-node"
            val elector = LettuceStrategicSuspendLeaderGroupElector(connection, nodeId)

            elector.registerCandidate(lockName, CandidateInfo(nodeId))
            elector.refreshCandidate(
                lockName,
                CandidateInfo(nodeId, metadata = mapOf("phase" to "refreshed")),
                2.seconds,
            )
            elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)

            val listed = elector.listCandidates(lockName).single()
            listed.metadata shouldBeEqualTo mapOf("phase" to "refreshed")
            listed.successCount shouldBeEqualTo 1L
            elector.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 1) { "winner" }
                .shouldBeEqualTo("winner")

            elector.unregisterCandidate(lockName, nodeId)
            elector.listCandidates(lockName).shouldBeEmpty()
        }
    }

    @Test
    fun `suspend group elector migrates a colon source before refreshing and unregistering`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-group-migration-${System.nanoTime()}"
            val nodeId = "cluster-suspend-group-migration-node"
            val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
            val legacyIndex = "${LettuceCandidateRegistry.GROUP_KEY_PREFIX}:$lockName"
            val legacyKey = "$legacyIndex:$nodeId"
            connection.sync().set(legacyKey, raw)
            connection.sync().sadd(legacyIndex, nodeId)

            val elector = LettuceStrategicSuspendLeaderGroupElector(connection, nodeId)
            elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
            elector.refreshCandidate(
                lockName,
                CandidateInfo(nodeId, metadata = mapOf("source" to "colon-refresh")),
                2.seconds,
            )
            elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)
            elector.listCandidates(lockName).single().successCount shouldBeEqualTo 1L

            elector.unregisterCandidate(lockName, nodeId)
            elector.listCandidates(lockName).shouldBeEmpty()
            connection.sync().get(legacyKey).shouldBeNull()
        }
    }

    @Test
    fun `cluster v3 namespaces use one slot for every elector`() {
        withCluster { _ ->
            val lockName = "cluster-slot-${System.nanoTime()}"
            val nodeId = "cluster-slot-node"
            val keys = listOf(
                LettuceCandidateKeyCodec.indexKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName),
                LettuceCandidateKeyCodec.candidateKey(
                    LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                    lockName,
                    nodeId,
                ),
                LettuceCandidateKeyCodec.indexKey(LettuceCandidateRegistry.GROUP_KEY_PREFIX, lockName),
                LettuceCandidateKeyCodec.candidateKey(
                    LettuceCandidateRegistry.GROUP_KEY_PREFIX,
                    lockName,
                    nodeId,
                ),
            )

            keys.map(SlotHash::getSlot).distinct().size shouldBeEqualTo 1
        }
    }

    @Test
    fun `blocking group elector preserves positive and persistent TTL boundaries`() {
        withCluster { connection ->
            val lockName = "cluster-group-ttl-${System.nanoTime()}"
            val nodeId = "cluster-group-ttl-node"
            val elector = LettuceStrategicLeaderGroupElector(connection, nodeId)
            val candidateKey = LettuceCandidateKeyCodec.candidateKey(
                LettuceCandidateRegistry.GROUP_KEY_PREFIX,
                lockName,
                nodeId,
            )

            elector.registerCandidate(lockName, CandidateInfo(nodeId), 2.seconds)
            connection.sync().pttl(candidateKey) shouldBeGreaterThan 0L

            elector.refreshCandidate(lockName, CandidateInfo(nodeId), Duration.ZERO)
            connection.sync().pttl(candidateKey) shouldBeEqualTo -1L
            elector.unregisterCandidate(lockName, nodeId)
        }
    }

    @Test
    fun `suspend single elector migrates a v2 source with positive TTL`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-migration-ttl-${System.nanoTime()}"
            val nodeId = "cluster-suspend-migration-ttl-node"
            val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
            val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            )
            val v2Index = LettuceCandidateKeyCodec.v2IndexKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
            )
            connection.sync().set(v2Key, raw, SetArgs.Builder.px(3_000))
            connection.sync().sadd(v2Index, nodeId)

            val sourceTtl = connection.sync().pttl(v2Key)
            sourceTtl shouldBeGreaterThan 0L
            LettuceStrategicSuspendLeaderElector(connection, nodeId)
                .listCandidates(lockName)
                .single()
                .nodeId shouldBeEqualTo nodeId

            val destinationKey = LettuceCandidateKeyCodec.candidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            )
            val destinationTtl = connection.sync().pttl(destinationKey)
            destinationTtl shouldBeGreaterThan 0L
            destinationTtl shouldBeLessOrEqualTo sourceTtl
            connection.sync().get(v2Key) shouldBeEqualTo raw
        }
    }

    @Test
    fun `suspend group elector migrates a colon source with positive TTL`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-group-migration-ttl-${System.nanoTime()}"
            val nodeId = "cluster-suspend-group-migration-ttl-node"
            val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
            val legacyIndex = "${LettuceCandidateRegistry.GROUP_KEY_PREFIX}:$lockName"
            val legacyKey = "$legacyIndex:$nodeId"
            connection.sync().set(legacyKey, raw, SetArgs.Builder.px(3_000))
            connection.sync().sadd(legacyIndex, nodeId)

            val sourceTtl = connection.sync().pttl(legacyKey)
            sourceTtl shouldBeGreaterThan 0L
            LettuceStrategicSuspendLeaderGroupElector(connection, nodeId)
                .listCandidates(lockName)
                .single()
                .nodeId shouldBeEqualTo nodeId

            val destinationKey = LettuceCandidateKeyCodec.candidateKey(
                LettuceSuspendCandidateRegistry.GROUP_KEY_PREFIX,
                lockName,
                nodeId,
            )
            val destinationTtl = connection.sync().pttl(destinationKey)
            destinationTtl shouldBeGreaterThan 0L
            destinationTtl shouldBeLessOrEqualTo sourceTtl
            connection.sync().get(legacyKey) shouldBeEqualTo raw
        }
    }

    @Test
    fun `suspend group elector propagates action cancellation without recording failure`() = runSuspendIO {
        withClusterSuspend { connection ->
            val lockName = "cluster-suspend-group-cancel-${System.nanoTime()}"
            val nodeId = "cluster-suspend-group-cancel-node"
            val elector = LettuceStrategicSuspendLeaderGroupElector(connection, nodeId)
            elector.registerCandidate(lockName, CandidateInfo(nodeId))
            val cancellation = CancellationException("cluster group action cancelled")

            val thrown = assertFailsWith<CancellationException> {
                elector.runIfLeader(lockName, FifoGroupElectionStrategy, maxLeaders = 1) {
                    throw cancellation
                }
            }
            thrown.message shouldBeEqualTo cancellation.message
            elector.listCandidates(lockName).single().failureCount shouldBeEqualTo 0L
        }
    }

    @Test
    fun `suspend cluster script cancellation preserves the caller connection`() = runSuspendIO {
        withClusterSuspend { connection ->
            val commands = LettuceSuspendCandidateCommands(connection.coroutines()) { connection.async() }
            val cancelled = Job()
            val cancellation = CancellationException("cluster script cancelled")
            cancelled.cancel(cancellation)

            val thrown = assertFailsWith<CancellationException> {
                withContext(cancelled) {
                    commands.runScript<String>(
                        RedisScript("return 'ok'"),
                        ScriptOutputType.VALUE,
                        emptyArray(),
                    )
                }
            }
            thrown.message shouldBeEqualTo cancellation.message
            connection.sync().ping() shouldBeEqualTo "PONG"
        }
    }

    @Test
    fun `blocking cluster migration preserves concurrent source and current writers`() = runSuspendIO {
        withClusterSuspend { connection ->
            MigrationSource.entries.forEach { source ->
                MigrationRace.entries.forEach { race ->
                    val scenario = MigrationRaceScenario(connection.sync(), source)
                    val registry = LettuceCandidateRegistry(scenario.wrap(connection))
                    scenario.verifyRace(race) { registry.listCandidates(scenario.lockName) }
                    connection.sync().ping() shouldBeEqualTo "PONG"
                }
            }
        }
    }

    @Test
    fun `suspend cluster migration preserves concurrent source and current writers`() = runSuspendIO {
        withClusterSuspend { connection ->
            MigrationSource.entries.forEach { source ->
                MigrationRace.entries.forEach { race ->
                    val scenario = MigrationRaceScenario(connection.sync(), source)
                    val registry = LettuceSuspendCandidateRegistry(scenario.wrap(connection))
                    scenario.verifyRace(race) { registry.listCandidates(scenario.lockName) }
                    connection.sync().ping() shouldBeEqualTo "PONG"
                }
            }
        }
    }

    private fun withCluster(block: (StatefulRedisClusterConnection<String, String>) -> Unit) {
        RedisClusterServer.Launcher.LettuceLib.getClusterClient(server).use { client ->
            client.connect().use { connection ->
                recordClusterRuntime(server, connection)
                block(connection)
            }
        }
    }

    private suspend fun withClusterSuspend(
        block: suspend (StatefulRedisClusterConnection<String, String>) -> Unit,
    ) {
        RedisClusterServer.Launcher.LettuceLib.getClusterClient(server).use { client ->
            client.connect().use { connection ->
                recordClusterRuntime(server, connection)
                block(connection)
            }
        }
    }

    private fun recordClusterRuntime(
        server: RedisClusterServer,
        connection: StatefulRedisClusterConnection<String, String>,
    ) {
        val diagnosticsDirectory = Path.of(
            System.getProperty(
                "redis.cluster.diagnostics.dir",
                "build/redis-cluster-diagnostics",
            ),
        )
        Files.createDirectories(diagnosticsDirectory)

        val actualImageId = server.dockerClient.inspectContainerCmd(server.containerId).exec().imageId
        val actualImage = server.dockerClient
            .inspectImageCmd(actualImageId)
            .exec()
        val requestedImage = server.dockerClient.inspectImageCmd(clusterImage).exec()
        actualImage.id shouldBeEqualTo requestedImage.id
        val imageDigest = actualImage.repoDigests
            .orEmpty()
            .firstOrNull { it == clusterImage }
            ?: error("Redis Cluster running image does not match pinned digest: $clusterImage")
        val clusterInfo = connection.sync().clusterInfo()
        val clusterState = clusterInfo.lineSequence()
            .firstOrNull { it.startsWith("cluster_state:") }
            ?.substringAfter(':')
            ?.trim()
            ?: "unknown"
        val endpoints = server.properties()["nodes"].orEmpty()
        val runtimeProvenance = buildString {
            appendLine("image=${server.dockerImageName}")
            appendLine("image_digest=$imageDigest")
            appendLine("image_id=$actualImageId")
            appendLine("fixture=io.bluetape4k.testcontainers.storage.RedisClusterServer")
            appendLine("cluster_state=$clusterState")
            appendLine("endpoints=$endpoints")
            appendLine("mapped_ports=${server.mappedPorts.entries.joinToString(",") { (source, mapped) -> "$source->$mapped" }}")
        }
        Files.writeString(diagnosticsDirectory.resolve("cluster-runtime.txt"), runtimeProvenance)
        Files.writeString(diagnosticsDirectory.resolve("cluster-info.txt"), clusterInfo)
    }
}

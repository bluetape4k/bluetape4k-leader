@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.testcontainers.storage.RedisClusterServer
import io.lettuce.core.RedisException
import io.lettuce.core.RedisURI
import io.lettuce.core.cluster.ClusterClientOptions
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.SlotHash
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 각 테스트가 전용 Cluster를 소유한다. 공유 Launcher에는 장애를 주입하지 않는다.
 * 표본과 수렴 시간은 이 fixture의 관측값이며 운영 환경의 지연·무손실 보장이 아니다.
 */
@Tag("redis-cluster")
@Timeout(180)
class LettuceRedisClusterRecoveryTest {

    @Test
    fun `slot migration emits real ASK and MOVED before client topology converges`() {
        withCluster("slot-migration") { server, client, connection, evidence ->
            val key = "{issue-879-slot}:probe"
            val slot = SlotHash.getSlot(key)
            val source = checkNotNull(client.partitions.getPartitionBySlot(slot))
            val destination = client.partitions.first { it.nodeId != source.nodeId && it.slots.isNotEmpty() }
            val sourcePort = portOf(server, source.nodeId)
            val destinationPort = portOf(server, destination.nodeId)
            val commands = connection.sync()
            commands.set(key, "preserved") shouldBeEqualTo "OK"
            samples("baseline", evidence) { commands.get(key) shouldBeEqualTo "preserved" }

            cli(server, destinationPort, "CLUSTER", "SETSLOT", "$slot", "IMPORTING", source.nodeId) shouldBeEqualTo "OK"
            cli(server, sourcePort, "CLUSTER", "SETSLOT", "$slot", "MIGRATING", destination.nodeId) shouldBeEqualTo "OK"
            cli(server, sourcePort, "MIGRATE", "127.0.0.1", "$destinationPort", key, "0", "2000") shouldBeEqualTo "OK"

            // -c를 사용하지 않는 직접 명령으로 서버의 redirect 원문을 검증한다.
            val ask = cli(server, sourcePort, "GET", key)
            ask shouldBeEqualTo "ASK $slot 127.0.0.1:$destinationPort"
            evidence += "ask=$ask"
            samples("ask", evidence) { commands.get(key) shouldBeEqualTo "preserved" }
            client.partitions.getPartitionBySlot(slot)?.nodeId shouldBeEqualTo source.nodeId

            val started = System.nanoTime()
            cli(server, destinationPort, "CLUSTER", "SETSLOT", "$slot", "NODE", destination.nodeId) shouldBeEqualTo "OK"
            cli(server, sourcePort, "CLUSTER", "SETSLOT", "$slot", "NODE", destination.nodeId) shouldBeEqualTo "OK"
            client.partitions.filter { it.slots.isNotEmpty() && it.nodeId !in setOf(source.nodeId, destination.nodeId) }
                .forEach { node ->
                    cli(server, portOf(server, node.nodeId), "CLUSTER", "SETSLOT", "$slot", "NODE", destination.nodeId) shouldBeEqualTo "OK"
                }
            val moved = cli(server, sourcePort, "GET", key)
            moved shouldBeEqualTo "MOVED $slot 127.0.0.1:$destinationPort"
            evidence += "moved=$moved"
            converge(evidence) {
                commands.get(key) shouldBeEqualTo "preserved"
                client.partitions.getPartitionBySlot(slot)?.nodeId shouldBeEqualTo destination.nodeId
            }
            evidence += "convergence_ms=${elapsedMillis(started)}"
            evidence += "owner_before=${source.nodeId};owner_after=${destination.nodeId};slot=$slot"
            samples("recovered", evidence) { commands.get(key) shouldBeEqualTo "preserved" }
            verifyElectors(connection, slot)
        }
    }

    @Test
    fun `primary process pause triggers automatic failover and caller recovery`() {
        withCluster("automatic-failover") { server, client, connection, evidence ->
            val key = "{issue-879-failover}:probe"
            val slot = SlotHash.getSlot(key)
            val source = checkNotNull(client.partitions.getPartitionBySlot(slot))
            val sourcePort = portOf(server, source.nodeId)
            val replica = client.partitions.first { it.slaveOf == source.nodeId }
            val replicaPort = portOf(server, replica.nodeId)
            RedisClusterServer.PORTS.forEach { port ->
                cli(server, port, "CONFIG", "SET", "cluster-node-timeout", "1000") shouldBeEqualTo "OK"
            }
            // 같은 TCP 연결에서 SET 뒤 WAIT를 실행해야 해당 쓰기의 복제를 확인할 수 있다.
            connection.getConnection(source.nodeId).sync().apply {
                set(key, "replicated") shouldBeEqualTo "OK"
                await.atMost(Duration.ofSeconds(10)).untilAsserted {
                    // 서버 WAIT는 caller 명령 timeout(1초) 안에 끝나야 한다.
                    waitForReplication(1, 500) shouldBeEqualTo 1L
                }
            }
            samples("baseline", evidence) { connection.sync().get(key) shouldBeEqualTo "replicated" }
            val pid = cli(server, sourcePort, "INFO", "server").lineSequence()
                .first { it.startsWith("process_id:") }.substringAfter(':').trim().toInt()
            pid.shouldBeGreaterThan(1)
            val started = System.nanoTime()
            // 원래 검증 실패가 있으면 재개 실패는 suppressed 예외로 보존한다.
            AutoCloseable {
                server.execInContainer("sh", "-c", "kill -CONT \"\$1\"", "signal", "$pid").exitCode shouldBeEqualTo 0
            }.use {
                server.execInContainer("sh", "-c", "kill -STOP \"\$1\"", "signal", "$pid").exitCode shouldBeEqualTo 0
                evidence += "fault=SIGSTOP;primary_pid=$pid;primary_port=$sourcePort"
                converge(evidence) {
                    cli(server, replicaPort, "INFO", "replication").contains("role:master").shouldBeTrue()
                    connection.sync().get(key) shouldBeEqualTo "replicated"
                    client.partitions.getPartitionBySlot(slot)?.nodeId shouldBeEqualTo replica.nodeId
                }
                evidence += "convergence_ms=${elapsedMillis(started)}"
                evidence += "owner_before=${source.nodeId};owner_after=${replica.nodeId};slot=$slot"
                samples("recovered", evidence) { connection.sync().get(key) shouldBeEqualTo "replicated" }
            }
            await.atMost(Duration.ofSeconds(30)).untilAsserted {
                cli(server, sourcePort, "INFO", "replication").contains("role:slave").shouldBeTrue()
            }
            verifyElectors(connection, slot)
        }
    }

    /** 동일 slot의 실제 blocking/suspend registry Lua 경로를 복구 뒤 검증한다. */
    private fun verifyElectors(connection: StatefulRedisClusterConnection<String, String>, slot: Int) = runSuspendIO {
        val lockName = (0..100_000).asSequence().map { "recovered-$it" }.first {
            SlotHash.getSlot(LettuceCandidateKeyCodec.indexKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, it)) == slot
        }
        val blocking = LettuceStrategicLeaderElector(connection, "blocking")
        blocking.registerCandidate(lockName, CandidateInfo("blocking"))
        blocking.refreshCandidate(lockName, CandidateInfo("blocking"))
        blocking.updateResult(lockName, "blocking", CandidateResult.SUCCESS)
        blocking.listCandidates(lockName).single().successCount shouldBeEqualTo 1L
        blocking.unregisterCandidate(lockName, "blocking")
        blocking.listCandidates(lockName).shouldBeEmpty()
        val suspending = LettuceStrategicSuspendLeaderElector(connection, "suspend")
        suspending.registerCandidate(lockName, CandidateInfo("suspend"))
        suspending.refreshCandidate(lockName, CandidateInfo("suspend"))
        suspending.updateResult(lockName, "suspend", CandidateResult.SUCCESS)
        suspending.listCandidates(lockName).single().successCount shouldBeEqualTo 1L
        suspending.unregisterCandidate(lockName, "suspend")
        suspending.listCandidates(lockName).shouldBeEmpty()
        connection.sync().ping() shouldBeEqualTo "PONG"
    }

    private fun samples(phase: String, evidence: MutableList<String>, operation: () -> Unit) {
        val started = System.nanoTime()
        var succeeded = 0
        try {
            repeat(10) { operation(); succeeded++ }
        } finally {
            evidence += "phase=$phase;requested=10;success=$succeeded;elapsed_ms=${elapsedMillis(started)}"
        }
    }

    private fun converge(evidence: MutableList<String>, operation: () -> Unit) {
        var attempts = 0
        var succeeded = 0
        try {
            await.atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted {
                attempts++
                try {
                    operation()
                    succeeded++
                } catch (failure: RedisException) {
                    evidence += "attempt=$attempts;error=${failure.javaClass.simpleName}"
                    throw AssertionError("Cluster 아직 수렴하지 않음", failure)
                }
            }
        } finally {
            evidence += "phase=convergence;attempts=$attempts;success=$succeeded"
        }
    }

    private fun portOf(server: RedisClusterServer, nodeId: String): Int =
        cli(server, 7000, "CLUSTER", "NODES").lineSequence().first { it.startsWith("$nodeId ") }
            .split(' ')[1].substringBefore('@').substringAfterLast(':').toInt()

    private fun cli(server: RedisClusterServer, port: Int, vararg command: String): String {
        val result = server.execInContainer("timeout", "5", "redis-cli", "--raw", "-p", "$port", *command)
        check(result.exitCode == 0) { "redis-cli ${command.first()}: ${result.stderr}" }
        return result.stdout.trim()
    }

    private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun withCluster(
        scenario: String,
        block: (RedisClusterServer, RedisClusterClient, StatefulRedisClusterConnection<String, String>, MutableList<String>) -> Unit,
    ) {
        val image = checkNotNull(javaClass.getResource("/redis-cluster-image.txt")).readText().trim()
        val server = RedisClusterServer(DockerImageName.parse(image))
        val evidence = mutableListOf("scenario=$scenario", "image_digest=$image", "periodic_refresh_ms=1000;adaptive_refresh_ms=100;command_timeout_ms=1000")
        val output = Path.of(System.getProperty("redis.cluster.diagnostics.dir", "build/redis-cluster-diagnostics"))
        try {
            server.start()
            val actualImage = server.dockerClient.inspectContainerCmd(server.containerId).exec().imageId
            actualImage shouldBeEqualTo server.dockerClient.inspectImageCmd(image).exec().id
            evidence += "image_id=$actualImage;endpoints=${server.properties()["nodes"]}"
            val resources = RedisClusterServer.Launcher.LettuceLib.clientResources(server)
            AutoCloseable { resources.shutdown().get(10, TimeUnit.SECONDS) }.use {
                RedisClusterClient.create(resources, server.mappedPorts.values.map {
                    RedisURI.create(server.host, it).apply { timeout = Duration.ofSeconds(1) }
                }).use { client ->
                    // 옵션은 caller가 소유한다. fixture 기본값이나 생산 코드의 설정을 바꾸지 않는다.
                    client.setOptions(ClusterClientOptions.builder().topologyRefreshOptions(
                        ClusterTopologyRefreshOptions.builder()
                            .adaptiveRefreshTriggersTimeout(Duration.ofMillis(100))
                            .enablePeriodicRefresh(Duration.ofSeconds(1)).build(),
                    ).build())
                    client.connect().use { connection -> block(server, client, connection, evidence) }
                }
            }
        } catch (failure: Throwable) {
            // 준비 단계 실패도 컨테이너 종료 전에 남긴다. 진단 실패가 원래 예외를 덮지 않는다.
            evidence += "failure=${failure.javaClass.name}:${failure.message}"
            runCatching {
                evidence += "endpoints=${server.properties()["nodes"]}"
                server.mappedPorts.forEach { (internalPort, hostPort) ->
                    evidence += "internal_port=$internalPort;info=${cli(server, internalPort, "CLUSTER", "INFO")}"
                    // Redis 명령 실패와 호스트 포트의 다른 프로토콜 응답을 구분하는 원시 진단이다.
                    val response = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(server.host, hostPort), 1000)
                            socket.soTimeout = 1000
                            socket.getOutputStream().write("*1\r\n\$4\r\nPING\r\n".toByteArray())
                            val bytes = ByteArray(128)
                            val count = socket.getInputStream().read(bytes)
                            if (count < 0) "EOF" else String(bytes, 0, count)
                        }
                    }.getOrElse { "${it.javaClass.simpleName}:${it.message}" }
                    evidence += "host_port=$hostPort;raw_ping=$response"
                }
            }.onFailure { failure.addSuppressed(it) }
            runCatching { server.logs.lines().takeLast(100).joinToString("\n") }
                .onSuccess { evidence += "container_log_tail:\n$it" }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        } finally {
            try {
                Files.createDirectories(output)
                Files.writeString(output.resolve("$scenario.txt"), evidence.joinToString("\n", postfix = "\n"))
            } finally {
                server.stop()
            }
        }
    }
}

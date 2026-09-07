package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldContain
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * source primary에 연결된 replica가 Lettuce topology에 나타날 때까지 제한된 시간 동안 기다린다.
 *
 * 준비 시간은 장애 주입 이후의 failover 수렴 시간과 분리해 기록하며, timeout 진단에는 마지막 node 관계와
 * slot 범위를 남긴다.
 */
internal fun awaitReplicaTopology(
    sourceNodeId: String,
    evidence: MutableList<String>,
    timeout: Duration = Duration.ofSeconds(10),
    topology: () -> Collection<RedisClusterNode>,
): RedisClusterNode {
    val started = System.nanoTime()
    var attempts = 0
    var replica: RedisClusterNode? = null
    var snapshot = "[]"
    try {
        await.atMost(timeout).pollInterval(Duration.ofMillis(100)).untilAsserted {
            attempts++
            val nodes = topology().toList()
            snapshot = describeTopology(nodes)
            replica = nodes.firstOrNull { it.slaveOf == sourceNodeId }
            val diagnosis = "Redis Cluster replica topology not ready: " +
                    "sourceNodeId=$sourceNodeId; topology=$snapshot"
            diagnosis shouldContain "slaveOf=$sourceNodeId"
        }
        return checkNotNull(replica)
    } finally {
        evidence += "phase=replica-readiness;attempts=$attempts;elapsed_ms=${elapsedMillis(started)};" +
                "source=$sourceNodeId;replica=${replica?.nodeId};topology=$snapshot"
    }
}

private fun describeTopology(nodes: Collection<RedisClusterNode>): String =
    nodes.joinToString(prefix = "[", postfix = "]") { node ->
        val slots = node.slots
        val slotRange = if (slots.isEmpty()) "none" else "${slots.first()}..${slots.last()}"
        "nodeId=${node.nodeId},role=${node.role},slaveOf=${node.slaveOf}," +
                "slotCount=${slots.size},slotRange=$slotRange"
    }

private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

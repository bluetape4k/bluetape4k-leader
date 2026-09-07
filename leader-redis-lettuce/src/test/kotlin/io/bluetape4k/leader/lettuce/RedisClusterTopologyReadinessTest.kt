package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldContain
import io.lettuce.core.cluster.models.partitions.RedisClusterNode
import io.lettuce.core.cluster.models.partitions.RedisClusterNode.NodeFlag
import org.awaitility.core.ConditionTimeoutException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class RedisClusterTopologyReadinessTest {

    @Test
    fun `replica 관계가 다음 topology snapshot에 나타나면 기다린 뒤 반환한다`() {
        val source = node("source", NodeFlag.UPSTREAM, slots = listOf(0, 1))
        val replica = node("replica", NodeFlag.REPLICA, slaveOf = "source")
        val attempts = AtomicInteger()
        val evidence = mutableListOf<String>()

        val actual = awaitReplicaTopology("source", evidence, Duration.ofSeconds(1)) {
            if (attempts.incrementAndGet() == 1) listOf(source) else listOf(source, replica)
        }

        actual.nodeId shouldBeEqualTo "replica"
        attempts.get() shouldBeGreaterThan 1
        val readiness = evidence.single()
        readiness shouldContain "phase=replica-readiness"
        readiness shouldContain "attempts=${attempts.get()}"
        readiness shouldContain "source=source;replica=replica"
    }

    @Test
    fun `replica 관계가 준비되지 않으면 마지막 topology 진단을 남긴다`() {
        val source = node("source", NodeFlag.UPSTREAM, slots = listOf(0, 1))
        val unrelated = node("replica", NodeFlag.REPLICA, slaveOf = "other")
        val evidence = mutableListOf<String>()

        val failure = assertFailsWith<ConditionTimeoutException> {
            awaitReplicaTopology("source", evidence, Duration.ofMillis(250)) {
                listOf(source, unrelated)
            }
        }

        failure.message.orEmpty() shouldContain "sourceNodeId=source"
        failure.message.orEmpty() shouldContain "nodeId=source"
        failure.message.orEmpty() shouldContain "role=UPSTREAM"
        failure.message.orEmpty() shouldContain "slaveOf=other"
        failure.message.orEmpty() shouldContain "slotCount=2"
        failure.message.orEmpty() shouldContain "slotRange=0..1"
        val readiness = evidence.single()
        readiness shouldContain "phase=replica-readiness"
        readiness shouldContain "source=source;replica=null"
        readiness shouldContain "slaveOf=other"
    }

    private fun node(
        nodeId: String,
        flag: NodeFlag,
        slaveOf: String? = null,
        slots: List<Int> = emptyList(),
    ): RedisClusterNode = RedisClusterNode().apply {
        this.nodeId = nodeId
        this.flags = setOf(flag)
        this.slaveOf = slaveOf
        this.slots = slots
    }
}

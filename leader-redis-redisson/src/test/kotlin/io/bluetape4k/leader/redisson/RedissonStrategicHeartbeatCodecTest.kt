package io.bluetape4k.leader.redisson

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import org.junit.jupiter.api.Test
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.redisson.codec.CompositeCodec
import org.redisson.codec.Kryo5Codec
import org.redisson.config.Config
import kotlin.time.Duration.Companion.seconds

class RedissonStrategicHeartbeatCodecTest : AbstractRedissonLeaderTest() {

    @Test
    fun `composite codec는 blocking과 suspend refresh에서 map key와 value encoder를 각각 사용한다`() = runSuspendIO {
        val client = createAsymmetricCodecClient()
        try {
            val blockingLockName = randomName()
            val blocking = RedissonStrategicLeaderElector(client, "blocking-node")
            val blockingCandidate = CandidateInfo("blocking-node", metadata = mapOf("source" to "stale"))

            blocking.registerCandidate(blockingLockName, blockingCandidate, ttl = 5.seconds)
            blocking.refreshCandidate(
                blockingLockName,
                blockingCandidate.copy(metadata = mapOf("source" to "fresh")),
                ttl = 5.seconds,
            )
            blocking.listCandidates(blockingLockName).single().metadata shouldBeEqualTo mapOf("source" to "fresh")

            val suspendLockName = randomName()
            val suspending = RedissonStrategicSuspendLeaderElector(client, "suspend-node")
            val suspendCandidate = CandidateInfo("suspend-node", metadata = mapOf("source" to "stale"))

            suspending.registerCandidate(suspendLockName, suspendCandidate, ttl = 5.seconds)
            suspending.refreshCandidate(
                suspendLockName,
                suspendCandidate.copy(metadata = mapOf("source" to "fresh")),
                ttl = 5.seconds,
            )
            suspending.listCandidates(suspendLockName).single().metadata shouldBeEqualTo mapOf("source" to "fresh")
        } finally {
            client.shutdown()
        }
    }

    private fun createAsymmetricCodecClient(): RedissonClient {
        val config = Config().apply {
            setCodec(CompositeCodec(StringCodec.INSTANCE, Kryo5Codec()))
            useSingleServer()
                .setAddress(redisUrl)
                .setConnectionPoolSize(8)
                .setConnectionMinimumIdleSize(2)
        }
        return Redisson.create(config)
    }
}

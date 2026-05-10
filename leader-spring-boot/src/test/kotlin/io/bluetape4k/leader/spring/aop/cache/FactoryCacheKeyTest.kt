package io.bluetape4k.leader.spring.aop.cache

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.logging.KLogging
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeEqualTo
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * [FactoryCacheKey] / [GroupFactoryCacheKey] — equals/hashCode + cross-backend collision 방지 검증.
 */
class FactoryCacheKeyTest {

    companion object: KLogging()

    @Test
    fun `같은 factory bean + 옵션 = 같은 key`() {
        val a = FactoryCacheKey("redissonLeaderElectionFactory", LeaderElectionOptions.Default)
        val b = FactoryCacheKey("redissonLeaderElectionFactory", LeaderElectionOptions.Default)
        a shouldBeEqualTo b
        a.hashCode() shouldBeEqualTo b.hashCode()
    }

    @Test
    fun `다른 factory bean - 같은 옵션 = 다른 key (cross-backend collision 방지)`() {
        val a = FactoryCacheKey("redissonLeaderElectionFactory", LeaderElectionOptions.Default)
        val b = FactoryCacheKey("lettuceLeaderElectionFactory", LeaderElectionOptions.Default)
        a shouldNotBeEqualTo b
    }

    @Test
    fun `같은 factory bean - 다른 waitTime = 다른 key`() {
        val a = FactoryCacheKey("X", LeaderElectionOptions(waitTime = 3.seconds))
        val b = FactoryCacheKey("X", LeaderElectionOptions(waitTime = 5.seconds))
        a shouldNotBeEqualTo b
    }

    @Test
    fun `같은 factory bean - 다른 leaseTime = 다른 key`() {
        val a = FactoryCacheKey("X", LeaderElectionOptions(leaseTime = 30.seconds))
        val b = FactoryCacheKey("X", LeaderElectionOptions(leaseTime = 60.seconds))
        a shouldNotBeEqualTo b
    }

    @Test
    fun `같은 factory bean - 다른 minLeaseTime = 다른 key`() {
        val a = FactoryCacheKey("X", LeaderElectionOptions(minLeaseTime = 5.seconds))
        val b = FactoryCacheKey("X", LeaderElectionOptions(minLeaseTime = 10.seconds))
        a shouldNotBeEqualTo b
    }

    @Test
    fun `같은 factory bean - 다른 autoExtend = 다른 key`() {
        val a = FactoryCacheKey("X", LeaderElectionOptions(autoExtend = false))
        val b = FactoryCacheKey("X", LeaderElectionOptions(autoExtend = true))
        a shouldNotBeEqualTo b
    }

    @Test
    fun `Group key - 같은 factory bean - 다른 maxLeaders = 다른 key`() {
        val a = GroupFactoryCacheKey("X", LeaderGroupElectionOptions(maxLeaders = 2))
        val b = GroupFactoryCacheKey("X", LeaderGroupElectionOptions(maxLeaders = 3))
        a shouldNotBeEqualTo b
    }

    @Test
    fun `Group key - 같은 factory bean - 다른 minLeaseTime = 다른 key`() {
        val a = GroupFactoryCacheKey("X", LeaderGroupElectionOptions(maxLeaders = 2, minLeaseTime = 5.seconds))
        val b = GroupFactoryCacheKey("X", LeaderGroupElectionOptions(maxLeaders = 2, minLeaseTime = 10.seconds))
        a shouldNotBeEqualTo b
    }
}

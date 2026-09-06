@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.strategy.CandidateInfo
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LettuceCandidateGenerationFenceTest : AbstractLettuceLeaderTest() {

    @Test
    fun `blocking register retires persistent v2 source after cleanup failure`() {
        verifyBlockingFence(LegacyLayout.V2, LegacyResidue.PERSISTENT_SOURCE)
    }

    @Test
    fun `blocking register retires ttl colon source after cleanup failure`() {
        verifyBlockingFence(LegacyLayout.COLON, LegacyResidue.TTL_SOURCE)
    }

    @Test
    fun `blocking register removes v2 index-only residue before opening a generation`() {
        verifyBlockingFence(LegacyLayout.V2, LegacyResidue.INDEX_ONLY)
    }

    @Test
    fun `suspend register retires persistent v2 source after cleanup failure`() = runSuspendIO {
        verifySuspendFence(LegacyLayout.V2, LegacyResidue.PERSISTENT_SOURCE)
    }

    @Test
    fun `suspend register retires ttl colon source after cleanup failure`() = runSuspendIO {
        verifySuspendFence(LegacyLayout.COLON, LegacyResidue.TTL_SOURCE)
    }

    @Test
    fun `suspend register removes colon index-only residue before opening a generation`() = runSuspendIO {
        verifySuspendFence(LegacyLayout.COLON, LegacyResidue.INDEX_ONLY)
    }

    @Test
    fun `blocking register rechecks the fence when unregister wins after observation`() {
        val fixture = prepareFixture(LegacyLayout.V2, LegacyResidue.PERSISTENT_SOURCE)
        val race = FenceRaceConnection(fixture)
        val registry = LettuceCandidateRegistry(race.wrappedConnection)

        registry.registerCandidate(fixture.lockName, CandidateInfo(fixture.nodeId), FRESH_TTL)

        race.wasTriggered shouldBeEqualTo true
        verifyFreshGeneration(fixture)
        awaitCurrentGenerationExpiry(fixture)
        registry.listCandidates(fixture.lockName).shouldBeEmpty()
    }

    @Test
    fun `suspend register rechecks the fence when unregister wins after observation`() = runSuspendIO {
        val fixture = prepareFixture(LegacyLayout.COLON, LegacyResidue.TTL_SOURCE)
        val race = FenceRaceConnection(fixture)
        val registry = LettuceSuspendCandidateRegistry(race.wrappedConnection)

        registry.registerCandidate(fixture.lockName, CandidateInfo(fixture.nodeId), FRESH_TTL)

        race.wasTriggered shouldBeEqualTo true
        verifyFreshGeneration(fixture)
        awaitCurrentGenerationExpiry(fixture)
        registry.listCandidates(fixture.lockName).shouldBeEmpty()
    }

    @Test
    fun `register preserves a colliding colon source owned by another candidate`() {
        val suffix = System.nanoTime().toString()
        val lockName = "generation-fence-$suffix"
        val nodeId = "hostname:pid"
        val collidingLockName = "$lockName:hostname"
        val collidingNodeId = "pid"
        val sharedSourceKey = LettuceCandidateKeyCodec.legacyCandidateKey(KEY_PREFIX, lockName, nodeId)
        val collidingRaw = LettuceCandidateInfoCodec.encode(CandidateInfo(collidingNodeId))
        val registry = LettuceCandidateRegistry(connection)

        registry.unregisterCandidate(lockName, nodeId)
        connection.sync().set(sharedSourceKey, collidingRaw)
        connection.sync().sadd(LettuceCandidateKeyCodec.legacyIndexKey(KEY_PREFIX, collidingLockName), collidingNodeId)

        registry.registerCandidate(lockName, CandidateInfo(nodeId), FRESH_TTL)

        connection.sync().get(sharedSourceKey) shouldBeEqualTo collidingRaw
        connection.sync().get(LettuceCandidateKeyCodec.candidateKey(KEY_PREFIX, lockName, nodeId)).shouldNotBeNull()
    }

    @Test
    fun `wrong-type legacy index cannot retain membership and does not block registration`() {
        val lockName = randomName()
        val nodeId = "node-${System.nanoTime()}"
        val legacyIndexKey = LettuceCandidateKeyCodec.legacyIndexKey(KEY_PREFIX, lockName)
        val registry = LettuceCandidateRegistry(connection)

        registry.unregisterCandidate(lockName, nodeId)
        connection.sync().set(legacyIndexKey, "not-a-set")

        registry.registerCandidate(lockName, CandidateInfo(nodeId), FRESH_TTL)

        connection.sync().type(legacyIndexKey) shouldBeEqualTo "string"
        connection.sync().get(LettuceCandidateKeyCodec.candidateKey(KEY_PREFIX, lockName, nodeId)).shouldNotBeNull()
    }

    private fun verifyBlockingFence(layout: LegacyLayout, residue: LegacyResidue) {
        val fixture = prepareFixture(layout, residue)
        val failure = CleanupFailureConnection(fixture.sourceKey, fixture.indexKey, residue.failure)
        val registry = LettuceCandidateRegistry(failure.wrappedConnection)

        assertFailsWith<IllegalStateException> {
            registry.unregisterCandidate(fixture.lockName, fixture.nodeId)
        }
        verifyFailedCleanup(fixture, failure, residue)

        registry.registerCandidate(fixture.lockName, CandidateInfo(fixture.nodeId), FRESH_TTL)
        verifyFreshGeneration(fixture)

        awaitCurrentGenerationExpiry(fixture)
        registry.listCandidates(fixture.lockName).shouldBeEmpty()
    }

    private suspend fun verifySuspendFence(layout: LegacyLayout, residue: LegacyResidue) {
        val fixture = prepareFixture(layout, residue)
        val failure = CleanupFailureConnection(fixture.sourceKey, fixture.indexKey, residue.failure)
        val registry = LettuceSuspendCandidateRegistry(failure.wrappedConnection)

        assertFailsWith<IllegalStateException> {
            registry.unregisterCandidate(fixture.lockName, fixture.nodeId)
        }
        verifyFailedCleanup(fixture, failure, residue)

        registry.registerCandidate(fixture.lockName, CandidateInfo(fixture.nodeId), FRESH_TTL)
        verifyFreshGeneration(fixture)

        awaitCurrentGenerationExpiry(fixture)
        registry.listCandidates(fixture.lockName).shouldBeEmpty()
    }

    private fun prepareFixture(layout: LegacyLayout, residue: LegacyResidue): Fixture {
        val lockName = randomName()
        val nodeId = "node-${System.nanoTime()}"
        val sourceKey = layout.candidateKey(lockName, nodeId)
        val indexKey = layout.indexKey(lockName)
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId, metadata = mapOf("generation" to "legacy")))

        connection.sync().set(sourceKey, raw)
        if (residue == LegacyResidue.TTL_SOURCE) {
            connection.sync().pexpire(sourceKey, LEGACY_TTL.inWholeMilliseconds)
        }
        connection.sync().sadd(indexKey, nodeId)

        return Fixture(lockName, nodeId, sourceKey, indexKey)
    }

    private fun verifyFailedCleanup(
        fixture: Fixture,
        failure: CleanupFailureConnection,
        residue: LegacyResidue,
    ) {
        failure.wasTriggered shouldBeEqualTo true
        connection.sync().get(fixture.tombstoneKey).shouldNotBeNull()
        connection.sync().get(fixture.currentCandidateKey).shouldBeNull()
        connection.sync().sismember(fixture.indexKey, fixture.nodeId) shouldBeEqualTo true
        if (residue == LegacyResidue.INDEX_ONLY) {
            connection.sync().get(fixture.sourceKey).shouldBeNull()
        } else {
            connection.sync().get(fixture.sourceKey).shouldNotBeNull()
        }
    }

    private fun verifyFreshGeneration(fixture: Fixture) {
        connection.sync().get(fixture.tombstoneKey).shouldBeNull()
        connection.sync().get(fixture.currentCandidateKey).shouldNotBeNull()
        connection.sync().sismember(fixture.currentIndexKey, fixture.nodeId) shouldBeEqualTo true
        connection.sync().get(fixture.sourceKey).shouldBeNull()
        connection.sync().sismember(fixture.indexKey, fixture.nodeId) shouldBeEqualTo false
    }

    private fun awaitCurrentGenerationExpiry(fixture: Fixture) {
        await.atMost(2.seconds).until {
            connection.sync().get(fixture.currentCandidateKey) == null
        }
    }

    private inner class CleanupFailureConnection(
        private val sourceKey: String,
        private val sourceIndexKey: String,
        private val failure: CleanupFailure,
    ) {
        private val armed = AtomicBoolean(true)
        private val triggered = AtomicBoolean(false)
        private val sync = AbstractLettuceLeaderTest.connection.sync()
        private val reactive = AbstractLettuceLeaderTest.connection.reactive()

        val wasTriggered: Boolean get() = triggered.get()

        private fun shouldFailDel(keys: Array<out String>): Boolean =
            failure == CleanupFailure.DEL && sourceKey in keys && triggerOnce()

        private fun shouldFailSrem(key: String, members: Array<out String>): Boolean =
            failure == CleanupFailure.SREM && key == sourceIndexKey && members.isNotEmpty() && triggerOnce()

        private fun triggerOnce(): Boolean = armed.compareAndSet(true, false).also { fired ->
            if (fired) triggered.set(true)
        }

        private val syncCommands = object : RedisCommands<String, String> by sync {
            override fun del(vararg keys: String): Long =
                if (shouldFailDel(keys)) error(INJECTED_FAILURE) else sync.del(*keys)

            override fun srem(key: String, vararg members: String): Long =
                if (shouldFailSrem(key, members)) error(INJECTED_FAILURE) else sync.srem(key, *members)
        }

        private val reactiveCommands = object : RedisReactiveCommands<String, String> by reactive {
            override fun del(vararg keys: String): Mono<Long> = Mono.defer {
                if (shouldFailDel(keys)) Mono.error(IllegalStateException(INJECTED_FAILURE)) else reactive.del(*keys)
            }

            override fun srem(key: String, vararg members: String): Mono<Long> = Mono.defer {
                if (shouldFailSrem(key, members)) {
                    Mono.error(IllegalStateException(INJECTED_FAILURE))
                } else {
                    reactive.srem(key, *members)
                }
            }
        }

        val wrappedConnection: StatefulRedisConnection<String, String> =
            object : StatefulRedisConnection<String, String> by AbstractLettuceLeaderTest.connection {
                override fun sync(): RedisCommands<String, String> = syncCommands
                override fun reactive(): RedisReactiveCommands<String, String> = reactiveCommands
            }
    }

    private inner class FenceRaceConnection(private val fixture: Fixture) {
        private val armed = AtomicBoolean(true)
        private val triggered = AtomicBoolean(false)
        private val sync = AbstractLettuceLeaderTest.connection.sync()
        private val reactive = AbstractLettuceLeaderTest.connection.reactive()

        val wasTriggered: Boolean get() = triggered.get()

        private fun getWithRace(key: String): String? {
            val observed = sync.get(key)
            if (key == fixture.tombstoneKey && armed.compareAndSet(true, false)) {
                injectUnregisterFence()
                triggered.set(true)
            }
            return observed
        }

        private fun injectUnregisterFence() {
            RedisScriptRunner.run<List<Any>>(
                sync,
                LettuceCandidateWriteScript.WRITE,
                ScriptOutputType.MULTI,
                arrayOf(
                    fixture.currentCandidateKey,
                    fixture.currentIndexKey,
                    fixture.tombstoneKey,
                    fixture.migrationTokenKey,
                ),
                LettuceCandidateWriteScript.UNREGISTER,
                fixture.nodeId,
                "race-${System.nanoTime()}",
            )
        }

        private val syncCommands = object : RedisCommands<String, String> by sync {
            override fun get(key: String): String? = getWithRace(key)
        }

        private val reactiveCommands = object : RedisReactiveCommands<String, String> by reactive {
            override fun get(key: String): Mono<String> = Mono.defer { Mono.justOrEmpty(getWithRace(key)) }
        }

        val wrappedConnection: StatefulRedisConnection<String, String> =
            object : StatefulRedisConnection<String, String> by AbstractLettuceLeaderTest.connection {
                override fun sync(): RedisCommands<String, String> = syncCommands
                override fun reactive(): RedisReactiveCommands<String, String> = reactiveCommands
            }
    }

    private enum class LegacyLayout {
        V2 {
            override fun candidateKey(lockName: String, nodeId: String): String =
                LettuceCandidateKeyCodec.v2CandidateKey(KEY_PREFIX, lockName, nodeId)

            override fun indexKey(lockName: String): String = LettuceCandidateKeyCodec.v2IndexKey(KEY_PREFIX, lockName)
        },
        COLON {
            override fun candidateKey(lockName: String, nodeId: String): String =
                LettuceCandidateKeyCodec.legacyCandidateKey(KEY_PREFIX, lockName, nodeId)

            override fun indexKey(lockName: String): String = LettuceCandidateKeyCodec.legacyIndexKey(KEY_PREFIX, lockName)
        },
        ;

        abstract fun candidateKey(lockName: String, nodeId: String): String
        abstract fun indexKey(lockName: String): String
    }

    private enum class LegacyResidue(val failure: CleanupFailure) {
        PERSISTENT_SOURCE(CleanupFailure.DEL),
        TTL_SOURCE(CleanupFailure.DEL),
        INDEX_ONLY(CleanupFailure.SREM),
    }

    private enum class CleanupFailure { DEL, SREM }

    private data class Fixture(
        val lockName: String,
        val nodeId: String,
        val sourceKey: String,
        val indexKey: String,
    ) {
        val currentCandidateKey: String = LettuceCandidateKeyCodec.candidateKey(KEY_PREFIX, lockName, nodeId)
        val currentIndexKey: String = LettuceCandidateKeyCodec.indexKey(KEY_PREFIX, lockName)
        val tombstoneKey: String = LettuceCandidateKeyCodec.tombstoneKey(KEY_PREFIX, lockName, nodeId)
        val migrationTokenKey: String = LettuceCandidateKeyCodec.migrationTokenKey(KEY_PREFIX, lockName, nodeId)
    }

    private companion object {
        const val KEY_PREFIX = LettuceCandidateRegistry.DEFAULT_KEY_PREFIX
        const val INJECTED_FAILURE = "injected legacy cleanup failure"
        val FRESH_TTL = 150.milliseconds
        val LEGACY_TTL = 30.seconds
    }
}

@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.logging.KLogging
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class LettuceCandidateWriteScriptTest: AbstractLettuceLeaderTest() {

    companion object: KLogging()

    @Test
    fun `register and unregister fence a candidate in one slot`() {
        val lockName = "write-script-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val value = LettuceCandidateInfoCodec.encode(
            io.bluetape4k.leader.strategy.CandidateInfo(nodeId),
        )

        val registered = run(keys, REGISTER, value, "0", nodeId)
        registered.status() shouldBeEqualTo REGISTERED
        connection.sync().get(keys.candidate).shouldNotBeNull()
        connection.sync().sismember(keys.index, nodeId).shouldBeTrue()

        val unregistered = run(keys, UNREGISTER, nodeId)
        unregistered.status() shouldBeEqualTo UNREGISTERED
        connection.sync().get(keys.candidate).shouldBeNull()
        connection.sync().get(keys.token).shouldBeNull()
        connection.sync().sismember(keys.index, nodeId).shouldBeFalse()
        connection.sync().get(keys.tombstone).shouldNotBeNull()
    }

    @Test
    fun `migration claims source payload with a token and matching cleanup removes only its value`() {
        val lockName = "write-script-migrate-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(
            io.bluetape4k.leader.strategy.CandidateInfo(nodeId),
        )
        val token = "migration-token-${Base58.randomString(8)}"

        val migrated = run(
            keys,
            MIGRATE,
            raw,
            "-1",
            nodeId,
            token,
        )
        migrated.status() shouldBeEqualTo MIGRATED
        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().get(keys.token) shouldBeEqualTo token

        val removed = run(
            keys.copyWithoutTombstone(),
            REMOVE_IF_VALUE,
            raw,
            token,
            nodeId,
        )
        removed.status() shouldBeEqualTo REMOVED
        connection.sync().get(keys.candidate).shouldBeNull()
        connection.sync().get(keys.token).shouldBeNull()
        connection.sync().sismember(keys.index, nodeId).shouldBeFalse()
    }

    @Test
    fun `regular refresh and result writers clear migration ownership token`() {
        val lockName = "write-script-token-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val original = CandidateInfo(nodeId)
        val raw = LettuceCandidateInfoCodec.encode(original)
        connection.sync().set(keys.candidate, raw)
        connection.sync().sadd(keys.index, nodeId)
        connection.sync().set(keys.token, "stale-token")

        val refreshed = RedisScriptRunner.run<List<Any>>(
            connection.sync(),
            LettuceCandidateRefreshScript.REFRESH,
            ScriptOutputType.MULTI,
            arrayOf(keys.candidate, keys.index, keys.token),
            LettuceCandidateInfoCodec.encode(original.copy(metadata = mapOf("phase" to "refresh"))),
            "0",
        )
        refreshed.first().toString().toLong() shouldBeEqualTo UPDATED
        connection.sync().get(keys.token).shouldBeNull()

        connection.sync().set(keys.token, "stale-token-2")
        val updated = RedisScriptRunner.run<List<Any>>(
            connection.sync(),
            LettuceCandidateResultScript.UPDATE,
            ScriptOutputType.MULTI,
            arrayOf(keys.candidate, keys.token),
            CandidateResult.SUCCESS.name,
            "123",
        )
        updated.first().toString().toLong() shouldBeEqualTo UPDATED
        connection.sync().get(keys.token).shouldBeNull()
        LettuceCandidateInfoCodec.decode(
            connection.sync().get(keys.candidate).shouldNotBeNull()
        ).successCount shouldBeEqualTo 1L
    }

    @Test
    fun `migration refuses a tombstoned node and does not resurrect source`() {
        val lockName = "write-script-tombstone-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(
            io.bluetape4k.leader.strategy.CandidateInfo(nodeId),
        )
        connection.sync().set(keys.tombstone, "1")

        val result = run(
            keys,
            MIGRATE,
            raw,
            "-1",
            nodeId,
            "token",
        )
        result.status() shouldBeEqualTo TOMBSTONED
        connection.sync().get(keys.candidate).shouldBeNull()
        connection.sync().get(keys.token).shouldBeNull()
        connection.sync().sismember(keys.index, nodeId).shouldBeFalse()
    }

    @Test
    fun `unregister barrier blocks migration until a fresh register clears the tombstone`() {
        val lockName = "write-script-barrier-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))

        run(keys, UNREGISTER, nodeId)
        val blocked = run(
            keys,
            MIGRATE,
            raw,
            "-1",
            nodeId,
            "blocked-token",
        )
        blocked.status() shouldBeEqualTo TOMBSTONED
        connection.sync().get(keys.candidate).shouldBeNull()

        run(keys, REGISTER, raw, "0", nodeId)
        connection.sync().get(keys.tombstone).shouldBeNull()
        connection.sync().get(keys.candidate) shouldBeEqualTo raw
    }

    @Test
    fun `matching payload with a replaced writer token cannot be cleaned by an old migration`() {
        val lockName = "write-script-token-owner-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))

        run(keys, MIGRATE, raw, "-1", nodeId, "old-token")
        run(keys, REGISTER, raw, "0", nodeId)

        val staleCleanup = run(
            keys,
            REMOVE_IF_VALUE,
            raw,
            "old-token",
            nodeId,
        )
        staleCleanup.status() shouldBeEqualTo ABSENT
        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().sismember(keys.index, nodeId).shouldBeTrue()
    }

    @Test
    fun `persistent source after a positive migration snapshot is not treated as expired`() {
        val lockName = "write-script-persistent-source-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val sourceKey = LettuceCandidateKeyCodec.v2CandidateKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
            nodeId,
        )
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
        connection.sync().set(sourceKey, raw)
        connection.sync().pexpire(sourceKey, 30_000L)
        connection.sync().sadd(
            LettuceCandidateKeyCodec.v2IndexKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName),
            nodeId,
        )
        val registry = LettuceCandidateRegistry(persistAfterFirstTtl(sourceKey))

        registry.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId

        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().get(keys.token).shouldNotBeNull()
        connection.sync().pttl(sourceKey) shouldBeEqualTo -1L
        connection.sync().sismember(keys.index, nodeId).shouldBeTrue()
    }

    @Test
    fun `suspend persistent source after a positive migration snapshot is not treated as expired`() = runSuspendIO {
        val lockName = "write-script-suspend-persistent-source-${Base58.randomString(8)}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val sourceKey = LettuceCandidateKeyCodec.v2CandidateKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
            nodeId,
        )
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
        connection.sync().set(sourceKey, raw)
        connection.sync().pexpire(sourceKey, 30_000L)
        connection.sync().sadd(
            LettuceCandidateKeyCodec.v2IndexKey(LettuceSuspendCandidateRegistry.DEFAULT_KEY_PREFIX, lockName),
            nodeId,
        )
        val registry = LettuceSuspendCandidateRegistry(persistAfterFirstTtl(sourceKey))

        registry.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId

        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().get(keys.token).shouldNotBeNull()
        connection.sync().pttl(sourceKey) shouldBeEqualTo -1L
        connection.sync().sismember(keys.index, nodeId).shouldBeTrue()
    }

    private fun run(keys: ScriptKeys, operation: String, vararg args: String): List<Any> =
        RedisScriptRunner.run(
            connection.sync(),
            LettuceCandidateWriteScript.WRITE,
            ScriptOutputType.MULTI,
            keys.forOperation(operation),
            operation,
            *args,
        )

    private fun List<Any>.status(): Long = first().toString().toLong()

    // 실제 PTTL 결과를 반환하되 첫 조회 직후 PERSIST를 완료해서 cleanup의 -1 경계를 재현한다.
    private fun persistAfterFirstTtl(sourceKey: String): StatefulRedisConnection<String, String> {
        val sync = connection.sync()
        val reactive = connection.reactive()
        var first = true
        fun persistSource(key: String, ttl: Long) {
            if (key == sourceKey && first) {
                ttl shouldBeGreaterThan 0L
                sync.persist(sourceKey).shouldBeTrue()
                first = false
            }
        }

        val syncCommands = object: RedisCommands<String, String> by sync {
            override fun pttl(key: String): Long = sync.pttl(key).also { persistSource(key, it) }
        }
        val reactiveCommands = object: RedisReactiveCommands<String, String> by reactive {
            override fun pttl(key: String): Mono<Long> = reactive.pttl(key).flatMap { ttl ->
                if (key == sourceKey && first) {
                    ttl shouldBeGreaterThan 0L
                    first = false
                    reactive.persist(sourceKey).map { persisted ->
                        persisted.shouldBeTrue()
                        ttl
                    }
                } else {
                    Mono.just(ttl)
                }
            }
        }
        return object: StatefulRedisConnection<String, String> by connection {
            override fun sync(): RedisCommands<String, String> = syncCommands
            override fun reactive(): RedisReactiveCommands<String, String> = reactiveCommands
        }
    }

    private fun keys(lockName: String, nodeId: String): ScriptKeys =
        ScriptKeys(
            LettuceCandidateKeyCodec.candidateKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName, nodeId),
            LettuceCandidateKeyCodec.indexKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName),
            LettuceCandidateKeyCodec.tombstoneKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName, nodeId),
            LettuceCandidateKeyCodec.migrationTokenKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            ),
        )

    private data class ScriptKeys(
        val candidate: String,
        val index: String,
        val tombstone: String,
        val token: String,
    ) {
        fun forOperation(operation: String): Array<String> =
            if (operation == REMOVE_IF_VALUE) {
                arrayOf(candidate, index, token)
            } else {
                arrayOf(candidate, index, tombstone, token)
            }

        fun copyWithoutTombstone(): ScriptKeys = this
    }
}

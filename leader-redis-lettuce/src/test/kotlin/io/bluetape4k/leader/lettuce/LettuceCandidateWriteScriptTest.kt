@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.lettuce.script.RedisScriptRunner
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.coroutines
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class LettuceCandidateWriteScriptTest : AbstractLettuceLeaderTest() {

    @Test
    fun `register and unregister fence a candidate in one slot`() {
        val lockName = "write-script-${System.nanoTime()}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val value = LettuceCandidateInfoCodec.encode(
            io.bluetape4k.leader.strategy.CandidateInfo(nodeId),
        )

        val registered = run(keys, LettuceCandidateWriteScript.REGISTER, value, "0", nodeId)
        registered.status() shouldBeEqualTo LettuceCandidateWriteScript.REGISTERED
        connection.sync().get(keys.candidate).shouldNotBeNull()
        connection.sync().sismember(keys.index, nodeId) shouldBeEqualTo true

        val unregistered = run(keys, LettuceCandidateWriteScript.UNREGISTER, nodeId)
        unregistered.status() shouldBeEqualTo LettuceCandidateWriteScript.UNREGISTERED
        connection.sync().get(keys.candidate).shouldBeNull()
        connection.sync().get(keys.token).shouldBeNull()
        connection.sync().sismember(keys.index, nodeId) shouldBeEqualTo false
        connection.sync().get(keys.tombstone).shouldNotBeNull()
    }

    @Test
    fun `migration claims source payload with a token and matching cleanup removes only its value`() {
        val lockName = "write-script-migrate-${System.nanoTime()}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(
            io.bluetape4k.leader.strategy.CandidateInfo(nodeId),
        )
        val token = "migration-token-${System.nanoTime()}"

        val migrated = run(
            keys,
            LettuceCandidateWriteScript.MIGRATE,
            raw,
            "-1",
            nodeId,
            token,
        )
        migrated.status() shouldBeEqualTo LettuceCandidateWriteScript.MIGRATED
        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().get(keys.token) shouldBeEqualTo token

        val removed = run(
            keys.copyWithoutTombstone(),
            LettuceCandidateWriteScript.REMOVE_IF_VALUE,
            raw,
            token,
            nodeId,
        )
        removed.status() shouldBeEqualTo LettuceCandidateWriteScript.REMOVED
        connection.sync().get(keys.candidate).shouldBeNull()
        connection.sync().get(keys.token).shouldBeNull()
        connection.sync().sismember(keys.index, nodeId) shouldBeEqualTo false
    }

    @Test
    fun `regular refresh and result writers clear migration ownership token`() {
        val lockName = "write-script-token-${System.nanoTime()}"
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
        refreshed.first().toString().toLong() shouldBeEqualTo LettuceCandidateRefreshScript.UPDATED
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
        updated.first().toString().toLong() shouldBeEqualTo LettuceCandidateResultScript.UPDATED
        connection.sync().get(keys.token).shouldBeNull()
        LettuceCandidateInfoCodec.decode(connection.sync().get(keys.candidate)!!).successCount shouldBeEqualTo 1L
    }

    @Test
    fun `migration refuses a tombstoned node and does not resurrect source`() {
        val lockName = "write-script-tombstone-${System.nanoTime()}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(
            io.bluetape4k.leader.strategy.CandidateInfo(nodeId),
        )
        connection.sync().set(keys.tombstone, "1")

        val result = run(
            keys,
            LettuceCandidateWriteScript.MIGRATE,
            raw,
            "-1",
            nodeId,
            "token",
        )
        result.status() shouldBeEqualTo LettuceCandidateWriteScript.TOMBSTONED
        connection.sync().get(keys.candidate).shouldBeNull()
        connection.sync().get(keys.token).shouldBeNull()
        connection.sync().sismember(keys.index, nodeId) shouldBeEqualTo false
    }

    @Test
    fun `unregister barrier blocks migration until a fresh register clears the tombstone`() {
        val lockName = "write-script-barrier-${System.nanoTime()}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))

        run(keys, LettuceCandidateWriteScript.UNREGISTER, nodeId)
        val blocked = run(
            keys,
            LettuceCandidateWriteScript.MIGRATE,
            raw,
            "-1",
            nodeId,
            "blocked-token",
        )
        blocked.status() shouldBeEqualTo LettuceCandidateWriteScript.TOMBSTONED
        connection.sync().get(keys.candidate).shouldBeNull()

        run(keys, LettuceCandidateWriteScript.REGISTER, raw, "0", nodeId)
        connection.sync().get(keys.tombstone).shouldBeNull()
        connection.sync().get(keys.candidate) shouldBeEqualTo raw
    }

    @Test
    fun `matching payload with a replaced writer token cannot be cleaned by an old migration`() {
        val lockName = "write-script-token-owner-${System.nanoTime()}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))

        run(keys, LettuceCandidateWriteScript.MIGRATE, raw, "-1", nodeId, "old-token")
        run(keys, LettuceCandidateWriteScript.REGISTER, raw, "0", nodeId)

        val staleCleanup = run(
            keys,
            LettuceCandidateWriteScript.REMOVE_IF_VALUE,
            raw,
            "old-token",
            nodeId,
        )
        staleCleanup.status() shouldBeEqualTo LettuceCandidateWriteScript.ABSENT
        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().sismember(keys.index, nodeId) shouldBeEqualTo true
    }

    @Test
    fun `persistent source after a positive migration snapshot is not treated as expired`() {
        val lockName = "write-script-persistent-source-${System.nanoTime()}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val sourceKey = LettuceCandidateKeyCodec.v2CandidateKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
            nodeId,
        )
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
        val token = "migration-token-${System.nanoTime()}"

        connection.sync().set(keys.candidate, raw)
        connection.sync().sadd(keys.index, nodeId)
        connection.sync().set(keys.token, token)
        connection.sync().set(sourceKey, raw)

        val constructor = LettuceCandidateRegistry::class.java.getDeclaredConstructor(
            BlockingCandidateCommands::class.java,
            BlockingCandidateValueReader::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val registry = constructor.newInstance(
            LettuceBlockingCandidateCommands(connection.sync()),
            BlockingCandidateValueReader { emptyMap() },
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
        )
        LettuceCandidateRegistry::class.java.getDeclaredMethod(
            "cleanupExpiredMigration",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            String::class.java,
        ).apply { isAccessible = true }
            .invoke(registry, lockName, nodeId, sourceKey, raw, 100L, token)

        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().get(keys.token) shouldBeEqualTo token
    }

    @Test
    fun `suspend persistent source after a positive migration snapshot is not treated as expired`() = runSuspendIO {
        val lockName = "write-script-suspend-persistent-source-${System.nanoTime()}"
        val nodeId = "node-1"
        val keys = keys(lockName, nodeId)
        val sourceKey = LettuceCandidateKeyCodec.v2CandidateKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
            nodeId,
        )
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId))
        val token = "suspend-migration-token-${System.nanoTime()}"

        connection.sync().set(keys.candidate, raw)
        connection.sync().sadd(keys.index, nodeId)
        connection.sync().set(keys.token, token)
        connection.sync().set(sourceKey, raw)

        val constructor = LettuceSuspendCandidateRegistry::class.java.getDeclaredConstructor(
            SuspendCandidateCommands::class.java,
            SuspendCandidateValueReader::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        val registry = constructor.newInstance(
            LettuceSuspendCandidateCommands(connection.coroutines()) { connection.async() },
            SuspendCandidateValueReader { emptyMap() },
            LettuceSuspendCandidateRegistry.DEFAULT_KEY_PREFIX,
        )

        invokeSuspendCleanup(registry, lockName, nodeId, sourceKey, raw, 100L, token)

        connection.sync().get(keys.candidate) shouldBeEqualTo raw
        connection.sync().get(keys.token) shouldBeEqualTo token
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

    private suspend fun invokeSuspendCleanup(
        registry: Any,
        lockName: String,
        nodeId: String,
        sourceKey: String,
        sourceRaw: String,
        observedTtl: Long,
        token: String,
    ): Any? {
        val method = LettuceSuspendCandidateRegistry::class.java.getDeclaredMethod(
            "cleanupExpiredMigration",
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            String::class.java,
            Continuation::class.java,
        ).apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            val result = method.invoke(
                registry,
                lockName,
                nodeId,
                sourceKey,
                sourceRaw,
                observedTtl,
                token,
                continuation,
            )
            if (result === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else result
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
            if (operation == LettuceCandidateWriteScript.REMOVE_IF_VALUE) {
                arrayOf(candidate, index, token)
            } else {
                arrayOf(candidate, index, tombstone, token)
            }

        fun copyWithoutTombstone(): ScriptKeys = this
    }
}

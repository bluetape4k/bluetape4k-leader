@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)
@file:Suppress("TooManyFunctions")

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScript
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.async.RedisScriptingAsyncCommands
import io.lettuce.core.cluster.api.coroutines.RedisClusterCoroutinesCommands
import io.lettuce.core.cluster.api.coroutines
import kotlinx.coroutines.flow.toList

/** suspend registry가 사용하는 direct command와 script capability입니다. */
internal interface SuspendCandidateCommands {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String): String?
    suspend fun set(key: String, value: String, args: SetArgs): String?
    suspend fun setnx(key: String, value: String): Boolean
    suspend fun psetex(key: String, ttlMillis: Long, value: String): String?
    suspend fun sadd(key: String, nodeId: String): Long
    suspend fun persist(key: String): Boolean
    suspend fun smembers(key: String): Set<String>
    suspend fun srem(key: String, nodeIds: Array<String>): Long
    suspend fun del(key: String): Long
    suspend fun pttl(key: String): Long

    suspend fun <T> runScript(
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T
}

/** standalone/Cluster coroutine direct command adapter입니다. */
internal class LettuceSuspendCandidateCommands(
    private val commands: RedisClusterCoroutinesCommands<String, String>,
    scriptCommands: () -> RedisScriptingAsyncCommands<String, String>,
) : SuspendCandidateCommands {

    private val scriptCommands by lazy(scriptCommands)

    override suspend fun get(key: String): String? = commands.get(key)

    override suspend fun set(key: String, value: String): String? = commands.set(key, value)

    override suspend fun set(key: String, value: String, args: SetArgs): String? = commands.set(key, value, args)

    override suspend fun setnx(key: String, value: String): Boolean = commands.setnx(key, value) == true

    override suspend fun psetex(key: String, ttlMillis: Long, value: String): String? =
        commands.psetex(key, ttlMillis, value)

    override suspend fun sadd(key: String, nodeId: String): Long = commands.sadd(key, nodeId) ?: 0L

    override suspend fun persist(key: String): Boolean = commands.persist(key) == true

    override suspend fun smembers(key: String): Set<String> = commands.smembers(key).toList().toSet()

    override suspend fun srem(key: String, nodeIds: Array<String>): Long = commands.srem(key, *nodeIds) ?: 0L

    override suspend fun del(key: String): Long = commands.del(key) ?: 0L

    override suspend fun pttl(key: String): Long = commands.pttl(key) ?: REDIS_KEY_ABSENT_TTL

    override suspend fun <T> runScript(
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = io.bluetape4k.leader.lettuce.script.RedisScriptRunner.runSuspending(
        scriptCommands,
        script,
        outputType,
        keys,
        *args,
    )
}

internal fun interface SuspendCandidateValueReader {
    suspend fun read(keys: List<String>): Map<String, String?>
}

/** standalone에서도 Lettuce coroutine API가 제공하는 MGET으로 후보를 일괄 조회합니다. */
internal class StandaloneSuspendCandidateValueReader(
    private val commands: RedisCoroutinesCommands<String, String>,
) : SuspendCandidateValueReader {
    override suspend fun read(keys: List<String>): Map<String, String?> =
        commands.mget(*keys.toTypedArray()).toList().associate { value ->
            value.key to if (value.hasValue()) value.value else null
        }
}

/** Cluster에서는 동일 lock hash-tag를 가진 v3 key를 coroutine MGET으로 읽습니다. */
internal class ClusterSuspendCandidateValueReader(
    private val commands: RedisClusterCoroutinesCommands<String, String>,
) : SuspendCandidateValueReader {
    override suspend fun read(keys: List<String>): Map<String, String?> =
        commands.mget(*keys.toTypedArray()).toList().associate { value ->
            value.key to if (value.hasValue()) value.value else null
        }
}

private const val REDIS_KEY_ABSENT_TTL = -2L

@file:Suppress("TooManyFunctions")

package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.lettuce.script.RedisScript
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.cluster.api.sync.RedisClusterCommands

/**
 * blocking registry가 사용하는 단일 key/set/script capability입니다.
 * `RedisCommands`는 `RedisClusterCommands`의 subtype이므로 standalone과 Cluster가
 * 동일한 mutation 경계를 공유하고, 일괄 조회만 별도 reader capability로 분리합니다.
 */
internal interface BlockingCandidateCommands {
    fun get(key: String): String?
    fun set(key: String, value: String): String?
    fun set(key: String, value: String, args: SetArgs): String?
    fun setnx(key: String, value: String): Boolean
    fun psetex(key: String, ttlMillis: Long, value: String): String?
    fun sadd(key: String, nodeId: String): Long
    fun persist(key: String): Boolean
    fun smembers(key: String): Set<String>
    fun srem(key: String, nodeIds: Array<String>): Long
    fun del(key: String): Long
    fun pttl(key: String): Long

    fun <T> runScript(
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T
}

/** standalone/Cluster sync API의 공통 command adapter입니다. */
internal class LettuceBlockingCandidateCommands(
    private val commands: RedisClusterCommands<String, String>,
) : BlockingCandidateCommands {

    override fun get(key: String): String? = commands.get(key)

    override fun set(key: String, value: String): String? = commands.set(key, value)

    override fun set(key: String, value: String, args: SetArgs): String? = commands.set(key, value, args)

    override fun setnx(key: String, value: String): Boolean = commands.setnx(key, value)

    override fun psetex(key: String, ttlMillis: Long, value: String): String? = commands.psetex(key, ttlMillis, value)

    override fun sadd(key: String, nodeId: String): Long = commands.sadd(key, nodeId)

    override fun persist(key: String): Boolean = commands.persist(key)

    override fun smembers(key: String): Set<String> = commands.smembers(key)

    override fun srem(key: String, nodeIds: Array<String>): Long = commands.srem(key, *nodeIds)

    override fun del(key: String): Long = commands.del(key)

    override fun pttl(key: String): Long = commands.pttl(key)

    override fun <T> runScript(
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = io.bluetape4k.leader.lettuce.script.RedisScriptRunner.run(
        commands,
        script,
        outputType,
        keys,
        *args,
    )
}

/** v3 일괄 candidate 조회에 필요한 Cluster 전용 capability입니다. */
internal fun interface BlockingCandidateValueReader {
    fun read(keys: List<String>): Map<String, String?>
}

/** standalone에서도 Lettuce sync API가 제공하는 MGET으로 후보를 일괄 조회합니다. */
internal class StandaloneBlockingCandidateValueReader(
    private val commands: RedisCommands<String, String>,
) : BlockingCandidateValueReader {
    override fun read(keys: List<String>): Map<String, String?> =
        commands.mget(*keys.toTypedArray()).associate { value ->
            value.key to if (value.hasValue()) value.value else null
        }
}

/** Cluster에서는 같은 lock hash-tag를 가진 v3 key만 MGET합니다. */
internal class ClusterBlockingCandidateValueReader(
    private val commands: io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands<String, String>,
) : BlockingCandidateValueReader {
    override fun read(keys: List<String>): Map<String, String?> =
        commands.mget(*keys.toTypedArray()).associate { value ->
            value.key to if (value.hasValue()) value.value else null
        }
}

private const val REDIS_KEY_ABSENT_TTL = -2L

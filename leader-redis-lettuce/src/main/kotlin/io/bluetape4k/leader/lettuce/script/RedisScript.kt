package io.bluetape4k.leader.lettuce.script

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.RedisClusterCommands
import io.lettuce.core.api.async.RedisScriptingAsyncCommands
import io.lettuce.core.api.sync.RedisScriptingCommands
import kotlinx.coroutines.future.await
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * `RedisScript`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property source Redis Lettuce backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class RedisScript(val source: String) {
    /**
     * `sha1` 값은 Redis Lettuce backend leader election 계약에서 사용하는 설정 또는 상태 항목입니다.
     */
    val sha1: String = sha1Hex(source)

    companion object: KLogging() {
        private fun sha1Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xff
                sb.append(Character.forDigit(v ushr 4, 16))
                sb.append(Character.forDigit(v and 0x0f, 16))
            }
            return sb.toString()
        }
    }
}

/**
 * `RedisScriptRunner`는 Redis Lettuce backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 */
@Suppress("TooManyFunctions")
object RedisScriptRunner : KLogging() {

    /**
     * `선언` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun <T> run(
        commands: RedisCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = runSync(commands, script, outputType, keys, *args)

    /** Cluster command hierarchy에 대한 additive sync overload입니다. */
    fun <T> run(
        commands: RedisClusterCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = runSync(commands, script, outputType, keys, *args)

    /** scripting capability만 노출하는 adapter를 위한 additive overload입니다. */
    fun <T> run(
        commands: RedisScriptingCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = runSync(commands, script, outputType, keys, *args)

    private fun <T> runSync(
        commands: RedisScriptingCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T {
        return try {
            commands.evalsha<T>(script.sha1, outputType, keys, *args)
        } catch (_: RedisNoScriptException) {
            log.debug { "NOSCRIPT → 원문 전송 fallback (sha1=${script.sha1})" }
            commands.eval<T>(script.source, outputType, keys, *args)
        }
    }

    /**
     * `선언` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    fun <T> runAsync(
        commands: RedisAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): CompletableFuture<T> = runAsyncInternal(commands, script, outputType, keys, *args)

    /** Cluster command hierarchy에 대한 additive async overload입니다. */
    fun <T> runAsync(
        commands: RedisClusterAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): CompletableFuture<T> = runAsyncInternal(commands, script, outputType, keys, *args)

    /** scripting capability만 노출하는 adapter를 위한 additive overload입니다. */
    fun <T> runAsync(
        commands: RedisScriptingAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): CompletableFuture<T> = runAsyncInternal(commands, script, outputType, keys, *args)

    private fun <T> runAsyncInternal(
        commands: RedisScriptingAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): CompletableFuture<T> {
        val future = commands.evalsha<T>(script.sha1, outputType, keys, *args).toCompletableFuture()
        return future.exceptionallyCompose { error ->
            val cause = if (error is CompletionException) error.cause ?: error else error
            if (cause is RedisNoScriptException) {
                log.debug { "NOSCRIPT(async) → 원문 전송 fallback (sha1=${script.sha1})" }
                commands.eval<T>(script.source, outputType, keys, *args).toCompletableFuture()
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
    }

    /**
     * `선언` 호출은 Redis Lettuce backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    suspend fun <T> runSuspending(
        commands: RedisAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = runSuspendingInternal(commands, script, outputType, keys, *args)

    /** Cluster command hierarchy에 대한 additive suspend overload입니다. */
    suspend fun <T> runSuspending(
        commands: RedisClusterAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = runSuspendingInternal(commands, script, outputType, keys, *args)

    /** scripting capability만 노출하는 adapter를 위한 additive overload입니다. */
    suspend fun <T> runSuspending(
        commands: RedisScriptingAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T = runSuspendingInternal(commands, script, outputType, keys, *args)

    private suspend fun <T> runSuspendingInternal(
        commands: RedisScriptingAsyncCommands<String, String>,
        script: RedisScript,
        outputType: ScriptOutputType,
        keys: Array<String>,
        vararg args: String,
    ): T {
        return try {
            commands.evalsha<T>(script.sha1, outputType, keys, *args).await()
        } catch (_: RedisNoScriptException) {
            log.debug { "NOSCRIPT(suspend) → 원문 전송 fallback (sha1=${script.sha1})" }
            commands.eval<T>(script.source, outputType, keys, *args).await()
        }
    }
}

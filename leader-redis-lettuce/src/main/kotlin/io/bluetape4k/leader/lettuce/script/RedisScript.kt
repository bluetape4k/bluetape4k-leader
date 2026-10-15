package io.bluetape4k.leader.lettuce.script

import io.bluetape4k.concurrent.failedCompletableFutureOf
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.async.RedisScriptingAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.api.sync.RedisScriptingCommands
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.RedisClusterCommands
import kotlinx.coroutines.future.await
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * `RedisScript`는 Redis `EVAL`/`EVALSHA` 실행에 사용할 Lua 원문과 소문자 SHA-1 식별자를 함께 제공합니다.
 *
 * @property source Redis에 `EVAL`로 전송할 Lua 원문입니다. key와 인자는 실행 메서드가 별도로 전달합니다.
 */
class RedisScript(val source: String) {
    /** `EVALSHA`에 사용할 source의 소문자 hexadecimal SHA-1 digest입니다. `NOSCRIPT`이면 원문 fallback을 실행합니다. */
    val sha1: String = sha1Hex(source)

    companion object: KLogging() {
        private fun sha1Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(text.toUtf8Bytes())

            return buildString(digest.size * 2) {
                for (b in digest) {
                    val v = b.toInt() and 0xff
                    append(Character.forDigit(v ushr 4, 16))
                    append(Character.forDigit(v and 0x0f, 16))
                }
            }
        }
    }
}

/**
 * `RedisScriptRunner`는 Redis Lua script를 sync/async/suspend 경로로 실행하고 `NOSCRIPT`일 때만 원문 `EVAL`로 재시도합니다.
 *
 */
@Suppress("TooManyFunctions")
object RedisScriptRunner: KLogging() {

    /** 먼저 `EVALSHA`를 실행하고 `RedisNoScriptException`일 때만 source를 `EVAL`로 재전송합니다. */
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

    /** async `EVALSHA`를 실행하며 `NOSCRIPT`만 source `EVAL`로 대체하고 그 밖의 오류는 failed future로 전달합니다. */
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
                failedCompletableFutureOf(cause)
            }
        }
    }

    /** suspend `EVALSHA`를 실행하며 `NOSCRIPT`일 때 source `EVAL`로 대체하고 나머지 오류는 호출자에게 전파합니다. */
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

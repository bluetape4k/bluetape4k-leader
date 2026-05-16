package io.bluetape4k.leader.lettuce.script

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.lettuce.core.RedisNoScriptException
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.future.await
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Represents a reusable Redis Lua script.
 *
 * Stores both the script source and its locally computed SHA1 hash, enabling execution via
 * `EVALSHA` instead of sending the full source on every call. When a [RedisNoScriptException]
 * occurs, it automatically retries by sending the full source (`EVAL`).
 *
 * @property source Lua script source
 */
class RedisScript(val source: String) {
    /** SHA1 hex string of `source` (identical to the result of Redis `SCRIPT LOAD`) */
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
 * Lua script execution helper usable from sync, async, and coroutine contexts.
 *
 * Tries `EVALSHA` first; automatically falls back to sending the full source on NOSCRIPT.
 */
object RedisScriptRunner: KLogging() {

    /** Sync: tries `EVALSHA` first; falls back to sending the full source on NOSCRIPT. */
    fun <T> run(
        commands: RedisCommands<String, String>,
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

    /** Async: tries `EVALSHA` first; returns a [CompletableFuture] with full-source fallback on NOSCRIPT. */
    fun <T> runAsync(
        commands: RedisAsyncCommands<String, String>,
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

    /** Coroutine: tries `EVALSHA` first; falls back to sending the full source on NOSCRIPT. */
    suspend fun <T> runSuspending(
        commands: RedisAsyncCommands<String, String>,
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

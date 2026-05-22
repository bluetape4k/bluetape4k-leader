package io.bluetape4k.leader.consul.internal

import java.time.Instant
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration

/**
 * Narrow internal boundary over Consul Session and KV HTTP APIs.
 */
internal interface ConsulLockClient {

    fun singleLockKey(lockName: String): String

    fun groupLockKey(lockName: String, slot: Int): String

    fun createSession(
        name: String,
        ttl: Duration,
        lockDelay: Duration,
    ): CompletableFuture<ConsulSessionId>

    fun acquire(
        key: String,
        sessionId: ConsulSessionId,
        ownerPayload: String,
    ): CompletableFuture<Boolean>

    fun release(
        key: String,
        sessionId: ConsulSessionId,
    ): CompletableFuture<Boolean>

    fun destroySession(sessionId: ConsulSessionId): CompletableFuture<Unit>

    fun renewSession(sessionId: ConsulSessionId): CompletableFuture<ConsulSessionRenewal>

    fun read(key: String): CompletableFuture<ConsulKvEntry?>
}

@JvmInline
internal value class ConsulSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Consul session id must not be blank." }
    }
}

internal data class ConsulSessionRenewal(
    val sessionId: ConsulSessionId,
    val renewedAt: Instant,
)

internal data class ConsulKvEntry(
    val key: String,
    val value: String?,
    val sessionId: ConsulSessionId?,
    val lockIndex: Long,
    val modifyIndex: Long,
)

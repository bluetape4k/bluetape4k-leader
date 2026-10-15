package io.bluetape4k.leader.consul.internal

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Consul session 생성/갱신/삭제와 KV lock acquire/release/read를 추상화한 클라이언트입니다. */
internal interface ConsulLockClient {

    val requestTimeout: Duration

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

internal fun <T> CompletableFuture<T>.getWithinRequestTimeout(lockClient: ConsulLockClient): T =
    get(lockClient.requestTimeout.inWholeNanoseconds.coerceAtLeast(1L), TimeUnit.NANOSECONDS)

@JvmInline
/**
 * `ConsulSessionId`는 Consul API가 반환한 non-blank session ID를 감싼 value class입니다.
 *
 * @property value Consul API가 반환한 non-blank session ID 문자열입니다.
 */
internal value class ConsulSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Consul session id must not be blank." }
    }
}

/**
 * `ConsulSessionRenewal`는 갱신한 session ID와 로컬 갱신 시각을 보관합니다.
 *
 * @property sessionId 갱신한 Consul session ID입니다.
 * @property renewedAt renew 요청이 성공한 로컬 시각입니다.
 */
internal data class ConsulSessionRenewal(
    val sessionId: ConsulSessionId,
    val renewedAt: Instant,
)

/**
 * `ConsulKvEntry`는 Consul KV 응답의 key, payload, session 및 operation index를 보관합니다.
 *
 * @property key Consul KV entry의 경로입니다.
 * @property value Base64 decoding 후의 optional KV payload 문자열입니다.
 * @property sessionId 해당 KV를 acquire한 optional Consul session ID입니다.
 * @property lockIndex Consul이 반환한 lock operation index입니다.
 * @property modifyIndex Consul이 반환한 마지막 수정 index입니다.
 */
internal data class ConsulKvEntry(
    val key: String,
    val value: String?,
    val sessionId: ConsulSessionId?,
    val lockIndex: Long,
    val modifyIndex: Long,
)

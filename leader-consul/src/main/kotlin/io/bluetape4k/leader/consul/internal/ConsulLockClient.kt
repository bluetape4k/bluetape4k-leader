package io.bluetape4k.leader.consul.internal

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Consul backend leader election 계약을 설명하는 한국어 KDoc입니다.
 */
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
 * `ConsulSessionId`는 Consul backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 value class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property value Consul backend 계약에서 `value` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal value class ConsulSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Consul session id must not be blank." }
    }
}

/**
 * `ConsulSessionRenewal`는 Consul backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 data class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property sessionId Consul backend 계약에서 `sessionId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property renewedAt Consul backend 계약에서 `renewedAt` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal data class ConsulSessionRenewal(
    val sessionId: ConsulSessionId,
    val renewedAt: Instant,
)

/**
 * `ConsulKvEntry`는 Consul backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 data class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property key Consul backend 계약에서 `key` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property value Consul backend 계약에서 `value` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property sessionId Consul backend 계약에서 `sessionId` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lockIndex Consul backend 계약에서 `lockIndex` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property modifyIndex Consul backend 계약에서 `modifyIndex` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal data class ConsulKvEntry(
    val key: String,
    val value: String?,
    val sessionId: ConsulSessionId?,
    val lockIndex: Long,
    val modifyIndex: Long,
)

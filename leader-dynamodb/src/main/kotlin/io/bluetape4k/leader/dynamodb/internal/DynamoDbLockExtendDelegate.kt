package io.bluetape4k.leader.dynamodb.internal

import io.bluetape4k.leader.ExtendOutcome
import io.bluetape4k.leader.internal.ExtendDelegate
import io.bluetape4k.leader.internal.SuspendExtendDelegate
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/**
 * `DynamoDbLockExtendDelegate`는 DynamoDB backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property client DynamoDB backend 계약에서 `client` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lock DynamoDB backend 계약에서 `lock` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property ttlPadding DynamoDB backend 계약에서 `ttlPadding` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal class DynamoDbLockExtendDelegate(
    private val client: DynamoDbLockClient,
    private val lock: DynamoDbLockClient.AcquiredLock,
    private val ttlPadding: Duration,
) : ExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override fun extend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            client.extend(lock, lockAtMostFor, ttlPadding)
        } catch (e: Exception) {
            log.warn(e) { "DynamoDB extend failed. key=${lock.key}" }
            ExtendOutcome.BackendError(e)
        }

    override fun isHeld(): Boolean =
        try {
            client.isHeld(lock)
        } catch (e: Exception) {
            log.warn(e) { "DynamoDB isHeld failed. key=${lock.key}" }
            false
        }
}

/**
 * `DynamoDbSuspendLockExtendDelegate`는 DynamoDB backend의 lease, session/TTL, owner 검증 상태를 보존하는 내부 class입니다.
 *
 * backend의 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 유지합니다.
 * @property client DynamoDB backend 계약에서 `client` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property lock DynamoDB backend 계약에서 `lock` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 * @property ttlPadding DynamoDB backend 계약에서 `ttlPadding` 값을 계산하거나 전달할 때 사용하는 속성입니다.
 */
internal class DynamoDbSuspendLockExtendDelegate(
    private val client: DynamoDbLockClient,
    private val lock: DynamoDbLockClient.AcquiredLock,
    private val ttlPadding: Duration,
) : SuspendExtendDelegate {

    companion object : KLogging()

    private val _lastExtendDeadline = AtomicReference(Instant.EPOCH)
    override val lastExtendDeadline: AtomicReference<Instant> get() = _lastExtendDeadline

    override suspend fun extendSuspend(lockAtMostFor: Duration): ExtendOutcome =
        try {
            client.extendAsync(lock, lockAtMostFor, ttlPadding).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "DynamoDB suspend extend failed. key=${lock.key}" }
            ExtendOutcome.BackendError(e)
        }

    override suspend fun isHeldSuspend(): Boolean =
        try {
            client.isHeldAsync(lock).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(e) { "DynamoDB suspend isHeld failed. key=${lock.key}" }
            false
        }
}

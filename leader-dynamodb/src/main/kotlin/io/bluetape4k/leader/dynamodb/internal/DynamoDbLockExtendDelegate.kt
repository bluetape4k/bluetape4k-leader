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

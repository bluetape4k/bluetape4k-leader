package io.bluetape4k.leader.identity

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Safely calls [LeaderIdProvider.nextLeaderId] and returns the result, with defensive fallbacks.
 *
 * ## Contract
 * - On [CancellationException]: rethrows immediately (structured concurrency contract).
 * - On [InterruptedException]: restores the thread interrupt flag and rethrows.
 * - On blank result from [provider]: logs a warning and returns [RandomLeaderIdProvider.Default] fallback.
 * - On any other [Exception]: logs an error and returns [RandomLeaderIdProvider.Default] fallback.
 *
 * ## Usage
 * ```kotlin
 * @OptIn(LeaderInternalApi::class)
 * val leaderId = safeNextLeaderId(provider, "my-lock")
 * ```
 *
 * @param provider the [LeaderIdProvider] to call
 * @param lockName the lock name passed to [provider]
 * @return a non-blank leader identity string
 */
@LeaderInternalApi
fun safeNextLeaderId(provider: LeaderIdProvider, lockName: String): String {
    return try {
        val result = provider.nextLeaderId(lockName)
        if (result.isBlank()) {
            log.warn { "[safeNextLeaderId] Provider returned blank for lockName='$lockName', falling back to default." }
            RandomLeaderIdProvider.Default.nextLeaderId(lockName)
        } else {
            result
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } catch (e: Exception) {
        log.error(e) { "[safeNextLeaderId] Provider threw for lockName='$lockName', falling back to default." }
        RandomLeaderIdProvider.Default.nextLeaderId(lockName)
    }
}

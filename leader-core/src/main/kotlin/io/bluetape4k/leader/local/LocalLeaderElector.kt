package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM 프로세스 내부에서 lock별 `ReentrantLock`으로 단일 leader 작업을 조정합니다.
 * 이 elector의 lock은 다른 프로세스와 공유되지 않습니다.
 * @property options node ID, lock 대기 시간, lease, 최소 유지 시간 및 자동 연장 설정
 */
class LocalLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): AbstractLocalLeaderElector(options), LeaderElector {

    companion object: KLogging()

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * @param lockName 획득할 JVM lock 이름
     * @param action lock을 보유한 동안 실행할 작업
     * @return lock을 얻으면 작업 결과, `options.waitTime` 안에 얻지 못하면 `null`
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? =
        tryWithLeaderLock(lockName, options.waitTime, action)

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * lock 획득과 action 실행을 `executor`에 맡깁니다. contention이면 future를 `null`로 완료하고, 획득 또는 action 오류는 예외 완료됩니다.
     * @param lockName 획득할 JVM lock 이름
     * @param executor lock 대기와 action을 실행할 executor
     * @param action lock을 얻은 뒤 실행할 비동기 작업
     * @return action의 결과 또는 contention 시 `null`로 완료되는 future
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "runAsyncIfLeader... lockName=$lockName" }

        val cancellationRelay = LeaderFutureBridge.cancellationRelay()

        return LeaderFutureBridge.propagateCancellation(
            futureOf(executor) {
                tryWithLeaderLock(lockName, options.waitTime) {
                    cancellationRelay.invoke(action).join()
                }
            },
            cancellationRelay,
        )
    }

    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "runAsyncIfLeader... slot=$slot" }

        val cancellationRelay = LeaderFutureBridge.cancellationRelay()

        return LeaderFutureBridge.propagateCancellation(
            futureOf(executor) {
                runIfLeader(slot) {
                    cancellationRelay.invoke(action).join()
                }
            },
            cancellationRelay,
        )
    }

    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        log.debug { "runAsyncIfLeaderResult... slot=$slot" }

        val elected = AtomicBoolean(false)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()

        return LeaderFutureBridge.map(
            runAsyncIfLeader(slot, executor) {
                elected.set(true)
                cancellationRelay.invoke(action)
            },
            cancellationRelay
        ) { value, failure ->
            when {
                failure != null && elected.get() -> failure.toActionFailedResult()
                failure != null -> throw failure.asCompletionException()
                elected.get()   -> LeaderRunResult.Elected(value, leaderId = slot.leaderId)
                else            -> LeaderRunResult.Skipped
            }
        }
    }

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * @param slot lock 이름과 상태 기록에 사용할 leader ID를 담은 slot
     * @param action lock을 보유한 동안 실행할 작업
     * @return lock을 얻으면 작업 결과, `options.waitTime` 안에 얻지 못하면 `null`
     */
    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        tryWithLeaderLock(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            waitTime = options.waitTime,
            action = action,
        )

    /**
     * `runIfLeaderResult`는 leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * action의 `Exception`은 `ActionFailed`로 반환하고, lock 획득 오류와 취소는 전파합니다.
     * @param slot lock 이름과 결과에 포함할 leader ID를 담은 slot
     * @param action lock을 보유한 동안 실행할 작업
     * @return 획득, contention skip, action 실패를 구분하는 `LeaderRunResult`
     */
    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        log.debug { "runAsyncIfLeaderResult... slot=$slot" }

        var elected = false
        val value = try {
            tryWithLeaderLock(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
                waitTime = options.waitTime,
            ) {
                elected = true
                action()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: Exception) {
            if (elected) {
                return LeaderRunResult.ActionFailed(e)
            }
            throw e
        }
        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId) else LeaderRunResult.Skipped
    }

    private fun Throwable.unwrapCompletionCause(): Throwable =
        (this as? CompletionException)?.cause ?: this

    private fun Throwable.toActionFailedResult(): LeaderRunResult.ActionFailed {
        val cause = unwrapCompletionCause()
        if (cause is CancellationException) {
            throw cause
        }
        return LeaderRunResult.ActionFailed(cause)
    }

    private fun Throwable.asCompletionException(): CompletionException =
        this as? CompletionException ?: CompletionException(this)
}

package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.AsyncLeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로세스 내부에서 설정된 최대 slot 수까지 동시 실행을 허용하는 `CompletableFuture` group elector입니다.
 *
 * @property options 최대 slot 수와 lease·대기 정책을 정하는 group election 설정입니다.
 */
class LocalAsyncLeaderGroupElector private constructor(
    options: LeaderGroupElectionOptions,
): AbstractLocalLeaderGroupElector(options), AsyncLeaderGroupElector {

    companion object: KLogging() {
        /**
         * 지정한 group election 정책으로 local async elector를 생성합니다.
         * @param options 최대 slot 수와 lease·대기 정책입니다. 기본값은 `LeaderGroupElectionOptions.Default`입니다.
         * @return 요청을 처리할 local async group elector입니다.
         */
        operator fun invoke(
            options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
        ): AsyncLeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("options.maxLeaders")
            return LocalAsyncLeaderGroupElector(options)
        }
    }

    /**
     * lock을 얻은 경우에만 async action을 실행하고, contention이면 `null`로 완료합니다.
     *
     * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
     * @param executor lock 획득과 사용자 작업을 비동기로 실행할 executor입니다.
     * @param action lock 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return lock을 얻지 못하면 `null`, 얻으면 action future의 값을 완료하는 future입니다.
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
                tryWithPermit(lockName) {
                    cancellationRelay.invoke(action).join()
                }
            },
            cancellationRelay,
        )
    }

    /**
     * 지정한 group slot을 얻은 경우에만 async action을 실행하고, contention이면 `null`로 완료합니다.
     *
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor slot 획득과 사용자 작업을 비동기로 실행할 executor입니다.
     * @param action slot 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return slot을 얻지 못하면 `null`, 얻으면 action future의 값을 완료하는 future입니다.
     */
    override fun <T> runAsyncIfLeader(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "runAsyncIfLeader... slot=$slot" }

        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.propagateCancellation(
            futureOf(executor) {
                tryWithPermit(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
                ) {
                    cancellationRelay.invoke(action).join()
                }
            },
            cancellationRelay,
        )
    }

    /**
     * `runAsyncIfLeaderResult`는 async leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * @param slot group election slot과 audit leader id를 함께 전달하는 값입니다.
     * @param executor slot 획득과 사용자 작업을 비동기로 실행할 executor입니다.
     * @param action slot 획득 후 호출되어 결과 future를 반환하는 사용자 작업입니다.
     * @return 성공·건너뜀·작업 실패를 구분하는 `LeaderRunResult`를 완료하는 future입니다.
     */
    override fun <T> runAsyncIfLeaderResult(
        slot: LeaderSlot,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<LeaderRunResult<T>> {
        log.debug { "runAsyncIfLeaderResult... slot=$slot" }

        val elected = AtomicBoolean(false)
        val cancellationRelay = LeaderFutureBridge.cancellationRelay()
        return LeaderFutureBridge.map(
            futureOf(executor) {
                tryWithPermit(
                    lockName = slot.lockName,
                    auditLeaderId = slot.leaderId,
                    nodeId = options.nodeId,
                ) {
                    elected.set(true)
                    cancellationRelay.invoke(action).join()
                }
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

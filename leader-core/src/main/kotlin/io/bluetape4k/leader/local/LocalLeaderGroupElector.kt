package io.bluetape4k.leader.local

import io.bluetape4k.concurrent.futureOf
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.internal.LeaderFutureBridge
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requirePositiveNumber
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * JVM 프로세스 안에서 lock별 semaphore로 동시에 실행할 수 있는 그룹 작업 수를 제한합니다.
 * 이 elector의 slot은 다른 프로세스와 공유되지 않습니다.
 */
class LocalLeaderGroupElector private constructor(options: LeaderGroupElectionOptions):
    AbstractLocalLeaderGroupElector(options),
    LeaderGroupElector {

    companion object: KLogging() {

        /**
         * 양수의 `maxLeaders`를 가진 local group elector를 생성합니다.
         * @param options node ID, 허용 slot 수, 대기 시간 및 lease 설정
         * @return 전달한 설정을 사용하는 JVM 내부 그룹 elector
         */
        operator fun invoke(options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default): LeaderGroupElector {
            options.maxLeaders.requirePositiveNumber("options.maxLeader")
            return LocalLeaderGroupElector(options)
        }
    }

    /**
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * @param lockName 획득할 JVM 그룹 lock 이름
     * @param action slot을 보유한 동안 실행할 작업
     * @return slot을 얻으면 작업 결과, `options.waitTime` 안에 얻지 못하면 `null`
     */
    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        return tryWithPermit(lockName, action)
    }

    /**
     * `runAsyncIfLeader`는 leadership을 획득한 경우에만 async action을 실행하고, 획득하지 못하면 null 결과를 완료합니다.
     *
     * permit 대기와 비동기 작업을 `executor`에서 실행합니다. contention이면 future를 `null`로 완료하고, 실행 오류는 예외 완료됩니다.
     * @param lockName 획득할 JVM 그룹 lock 이름
     * @param executor permit 대기와 action을 실행할 executor
     * @param action slot을 획득한 뒤 실행할 비동기 작업
     * @return action 결과 또는 contention 시 `null`로 완료되는 future
     */
    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        log.debug { "runAsyncIfLeader... lockName: $lockName" }

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
     * `runIfLeader`는 leadership을 획득한 경우에만 action을 실행하고, 획득하지 못하면 null을 반환합니다.
     *
     * slot의 leader ID를 상태와 결과에 보존합니다.
     * @param slot 그룹 lock 이름과 leader ID를 담은 slot
     * @param action slot을 보유한 동안 실행할 작업
     * @return slot을 얻으면 작업 결과, 대기 시간 안에 얻지 못하면 `null`
     */
    override fun <T> runIfLeader(slot: LeaderSlot, action: () -> T): T? =
        tryWithPermit(
            lockName = slot.lockName,
            auditLeaderId = slot.leaderId,
            nodeId = options.nodeId,
            action = action,
        )

    /**
     * `runIfLeaderResult`는 leadership 획득, skip, action 실패를 명시적인 LeaderRunResult로 반환합니다.
     *
     * action의 `Exception`은 `ActionFailed`로 반환하고 lock 획득 중단과 interrupt는 전파합니다.
     * @param slot 그룹 lock 이름과 결과에 포함할 leader ID를 담은 slot
     * @param action slot을 보유한 동안 실행할 작업
     * @return 획득, contention skip, action 실패를 구분하는 `LeaderRunResult`
     */
    override fun <T> runIfLeaderResult(slot: LeaderSlot, action: () -> T): LeaderRunResult<T> {
        log.debug { "runAsyncIfLeaderResult... slot=$slot" }

        var elected = false

        val value = try {
            tryWithPermit(
                lockName = slot.lockName,
                auditLeaderId = slot.leaderId,
                nodeId = options.nodeId,
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

        return if (elected) LeaderRunResult.Elected(value, leaderId = slot.leaderId)
        else LeaderRunResult.Skipped
    }
}

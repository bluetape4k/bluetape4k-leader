package io.bluetape4k.leader.local

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.support.requireNotBlank
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 로컬(단일 JVM) 리더 선출 구현체들의 공통 락 관리를 제공하는 추상 클래스입니다.
 *
 * ## 역할
 * - `lockName`별 [ReentrantLock] 풀을 [ConcurrentHashMap]으로 관리합니다.
 * - 하위 클래스는 [getLock]을 이용해 락을 획득/반환하고 실행 로직을 구현합니다.
 *
 * ## 하위 클래스
 * - [LocalLeaderElection]: 동기 + 비동기([java.util.concurrent.CompletableFuture]) 실행
 * - [LocalAsyncLeaderElection]: 비동기([java.util.concurrent.CompletableFuture]) 실행만
 * - [LocalVirtualThreadLeaderElection]: [io.bluetape4k.concurrent.virtualthread.VirtualFuture] 실행
 */
abstract class AbstractLocalLeaderElection(
    protected val options: LeaderElectionOptions = LeaderElectionOptions.Default,
) {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * [lockName]에 대한 [ReentrantLock]을 반환합니다. 없으면 새로 생성합니다.
     *
     * ```kotlin
     * val lock = getLock("job-lock")
     * lock.withLock { /* 임계 영역 */ }
     * ```
     *
     * @param lockName 락 이름 (blank 불가)
     * @return 해당 lockName에 대한 [ReentrantLock] 인스턴스
     */
    protected fun getLock(lockName: String): ReentrantLock {
        lockName.requireNotBlank("lockName")
        return locks.computeIfAbsent(lockName) { ReentrantLock() }
    }

    /**
     * [lockName]의 락을 획득한 상태에서 [action]을 실행합니다.
     *
     * ```kotlin
     * val result = withLeaderLock("job-lock") { "done" }
     * // result == "done"
     * ```
     *
     * @param lockName 락 이름
     * @param action 락을 획득한 상태에서 실행할 작업
     * @return [action] 실행 결과
     */
    protected inline fun <T> withLeaderLock(lockName: String, action: () -> T): T =
        getLock(lockName).withLock(action)

    /**
     * [lockName]의 락을 [waitTime] 내에 획득하면 [action]을 실행하고, 실패하면 `null`을 반환합니다.
     *
     * ```kotlin
     * val result = tryWithLeaderLock("job-lock", Duration.ofSeconds(1)) { "done" }
     * // result == "done" (획득 성공) 또는 null (획득 실패)
     * ```
     *
     * @param lockName 락 이름
     * @param waitTime 락 획득 최대 대기 시간
     * @param action 락 획득 성공 시 실행할 작업
     * @return [action] 실행 결과, 획득 실패 시 `null`
     */
    protected inline fun <T> tryWithLeaderLock(lockName: String, waitTime: Duration, action: () -> T): T? {
        val lock = getLock(lockName)
        val acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS)
        if (!acquired) return null
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }
}

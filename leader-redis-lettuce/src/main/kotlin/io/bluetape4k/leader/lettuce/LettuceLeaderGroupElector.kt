package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderGroupElector
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.LeaderGroupState
import io.bluetape4k.leader.lettuce.semaphore.LettuceSlotTokenGroup
import io.bluetape4k.leader.remainingMinLeaseTime
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * [StatefulRedisConnection]에서 [LettuceLeaderGroupElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3)
 * val election = connection.leaderGroupElection(options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param options    리더 선출 옵션 (기본값: [LeaderGroupElectionOptions.Default])
 * @return [LettuceLeaderGroupElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.leaderGroupElection(
    options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LettuceLeaderGroupElector =
    LettuceLeaderGroupElector(this, options)


/**
 * Lettuce Redis 클라이언트 기반의 복수 리더 선출 구현체입니다.
 *
 * ## 동작/계약
 *
 * - 내부적으로 [LettuceSlotTokenGroup] (ZSET + Lua) 을 사용하여 slot-token 모델로 동작합니다.
 * - 각 acquire 는 고유 token 을 반환하며, release 시 token 으로 정확한 슬롯을 식별합니다.
 * - `options.minLeaseTime > 0` 이면 빠른 action 종료 시 slot 의 score 만 갱신하여
 *   `minLeaseTime` 동안 backend TTL 로 슬롯을 유지합니다 (caller-park 없음).
 * - 클라이언트 crash 시 (release 미호출) leaseTime 만료 후 다음 acquire 시 자동 회수됩니다.
 * - `runAsyncIfLeader` 가 반환한 [CompletableFuture] 는 action 완료 + slot release 가 모두 끝난 후에만
 *   complete 됩니다. 따라서 chained `runAsyncIfLeader` 호출은 slot 이 freed 된 이후 시점에 acquire 를 시도합니다.
 *   release 자체의 실패는 warn log 로 남기되 outer future 의 결과에는 영향을 주지 않습니다.
 *
 * ```kotlin
 * val options = LeaderGroupElectionOptions(maxLeaders = 3, minLeaseTime = 1.seconds)
 * val election = LettuceLeaderGroupElector(connection, options)
 * val result = election.runIfLeader("batch-job") { processChunk() }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (maxLeaders, waitTime, leaseTime, minLeaseTime)
 */
class LettuceLeaderGroupElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderGroupElectionOptions = LeaderGroupElectionOptions.Default,
): LeaderGroupElector {

    companion object: KLogging()

    override val maxLeaders: Int = options.maxLeaders

    // lockName 별 slot-token group 을 1회만 생성하여 재사용합니다.
    private val slotGroups = ConcurrentHashMap<String, LettuceSlotTokenGroup>()

    private fun getSlotGroup(lockName: String): LettuceSlotTokenGroup {
        lockName.requireNotBlank("lockName")
        return slotGroups.computeIfAbsent(lockName) {
            LettuceSlotTokenGroup(connection, it, maxLeaders)
        }
    }

    override fun activeCount(lockName: String): Int = getSlotGroup(lockName).activeCount()

    override fun availableSlots(lockName: String): Int = getSlotGroup(lockName).availableSlots()

    override fun state(lockName: String): LeaderGroupState =
        LeaderGroupState(lockName, maxLeaders, activeCount(lockName))

    override fun <T> runIfLeader(lockName: String, action: () -> T): T? {
        val slotGroup = getSlotGroup(lockName)

        val token = slotGroup.tryAcquire(options.waitTime, options.leaseTime)
        if (token == null) {
            log.debug { "리더 선출 실패 (슬롯 없음): lockName=$lockName" }
            return null
        }
        // Codex P2: acquire 성공 후 startedAtNanos 캡처. acquire 전 캡처 시 waitTime 이 minLease 에서 차감.
        val startedAtNanos = System.nanoTime()
        log.debug { "리더 선출 성공: lockName=$lockName, token=$token" }
        try {
            return action()
        } finally {
            val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
            try {
                slotGroup.release(token, remainingMs)
            } catch (e: Throwable) {
                log.warn(e) { "Failed to release slot. lockName=$lockName, token=$token" }
            }
        }
    }

    override fun <T> runAsyncIfLeader(
        lockName: String,
        executor: Executor,
        action: () -> CompletableFuture<T>,
    ): CompletableFuture<T?> {
        val slotGroup = getSlotGroup(lockName)

        return slotGroup.tryAcquireAsync(options.waitTime, options.leaseTime).thenComposeAsync({ token ->
            if (token == null) {
                log.debug { "리더 선출 실패 (슬롯 없음, async): lockName=$lockName" }
                CompletableFuture.completedFuture<T?>(null)
            } else {
                // Codex P2: acquire 성공 후 startedAtNanos 캡처
                val startedAtNanos = System.nanoTime()
                log.debug { "리더 선출 성공 (async): lockName=$lockName, token=$token" }

                // Codex P2-2: action 결과(성공/실패)와 무관하게 release 완료까지 대기한 뒤 outer future 를 complete.
                // 기존 구현은 release 를 fire-and-forget 으로 trigger 하여 chained 호출이 slot 이 아직 점유된 채로 보일 수 있었음.
                val actionFuture: CompletableFuture<T> = try {
                    action()
                } catch (e: Throwable) {
                    // action 자체가 sync throw 한 경우에도 release 를 sequential 하게 chain.
                    return@thenComposeAsync releaseAndPropagate<T>(slotGroup, lockName, token, startedAtNanos, e, null)
                }

                actionFuture.handle<Pair<T?, Throwable?>> { value, error ->
                    Pair(value, error)
                }.thenCompose { (value, error) ->
                    releaseAndPropagate<T>(slotGroup, lockName, token, startedAtNanos, error, value)
                }
            }
        }, executor)
    }

    /**
     * action 종료 후 slot 을 release 하고, release 완료 시점에 결과(value 또는 error)로 future 를 complete 합니다.
     *
     * release 자체의 실패는 warn log 로만 남기고 outer future 의 결과에 영향을 주지 않습니다 — caller 는 action
     * 의 성공/실패만 알면 충분하기 때문입니다. release 완료 전에는 outer future 가 complete 되지 않으므로
     * 동일 elector 에 대한 chained `runAsyncIfLeader` 호출은 slot 이 정확히 freed 된 이후에만 acquire 를 시도합니다.
     */
    private fun <T> releaseAndPropagate(
        slotGroup: LettuceSlotTokenGroup,
        lockName: String,
        token: String,
        startedAtNanos: Long,
        error: Throwable?,
        value: T?,
    ): CompletableFuture<T?> {
        val remainingMs = remainingMinLeaseTime(startedAtNanos, options.minLeaseTime).inWholeMilliseconds
        return slotGroup.releaseAsync(token, remainingMs)
            .exceptionally { releaseError ->
                log.warn(releaseError) { "Failed to release slot. lockName=$lockName, token=$token" }
                Unit
            }
            .thenCompose {
                if (error != null) {
                    CompletableFuture.failedFuture(error)
                } else {
                    CompletableFuture.completedFuture<T?>(value)
                }
            }
    }
}

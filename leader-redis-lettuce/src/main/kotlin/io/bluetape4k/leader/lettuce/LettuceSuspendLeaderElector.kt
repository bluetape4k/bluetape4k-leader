package io.bluetape4k.leader.lettuce

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderLeaseAutoExtender
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.warn
import io.bluetape4k.leader.lettuce.lock.LettuceSuspendLock
import io.bluetape4k.support.requireNotBlank
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [StatefulRedisConnection]에서 [LettuceSuspendLeaderElector] 인스턴스를 생성합니다.
 *
 * ```kotlin
 * val election = connection.suspendLeaderElector()
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param options 리더 선출 옵션 (기본값: [LeaderElectionOptions.Default])
 * @return [LettuceSuspendLeaderElector] 인스턴스
 */
fun StatefulRedisConnection<String, String>.suspendLeaderElector(
    options: LeaderElectionOptions = LeaderElectionOptions.Default,
): LettuceSuspendLeaderElector =
    LettuceSuspendLeaderElector(this, options)


/**
 * Lettuce Redis 클라이언트를 이용한 코루틴 기반 리더 선출 구현체입니다.
 *
 * [LettuceSuspendLock]을 사용하여 비동기적으로 리더를 선출합니다.
 *
 * ```kotlin
 * val election = LettuceSuspendLeaderElector(connection)
 * val result = election.runIfLeader("daily-job") { "done" }
 * ```
 *
 * @param connection Lettuce [StatefulRedisConnection] (StringCodec 기반)
 * @param options    리더 선출 옵션 (waitTime, leaseTime)
 */
class LettuceSuspendLeaderElector(
    private val connection: StatefulRedisConnection<String, String>,
    val options: LeaderElectionOptions = LeaderElectionOptions.Default,
): SuspendLeaderElector {

    companion object: KLogging()

    override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
        lockName.requireNotBlank("lockName")

        val lock = LettuceSuspendLock(connection, lockName, options.leaseTime)
        val acquired = lock.tryLock(options.waitTime, options.leaseTime)
        if (!acquired) {
            log.debug { "리더 선출 실패 (슬롯 없음, suspend): lockName=$lockName" }
            return null
        }
        return coroutineScope {
            val acquiredAtNanos = System.nanoTime()
            val watchdog = if (options.autoExtend) {
                launch {
                    val period = LeaderLeaseAutoExtender.renewalPeriod(options.leaseTime)
                    while (isActive) {
                        delay(period)
                        val extended = try {
                            lock.extend(options.leaseTime)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log.warn(e) { "leader.lease.auto-extend.failed lockName=$lockName" }
                            false
                        }
                        if (!extended) {
                            log.warn { "leader.lease.auto-extend.stopped lockName=$lockName reason=NOT_OWNER" }
                            break
                        }
                    }
                }
            } else {
                null
            }
            log.debug { "리더 선출 성공 (suspend): lockName=$lockName" }
            try {
                action()
            } finally {
                // NonCancellable: 코루틴 취소 시에도 락 해제가 중단되지 않도록 보호
                withContext(NonCancellable) {
                    watchdog?.cancelAndJoin()
                    try {
                        if (lock.isHeldByCurrentInstance()) {
                            lock.unlock(options.minLeaseTime, acquiredAtNanos)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn(e) { "Fail to release lock. lockName=$lockName" }
                    }
                }
            }
        }
    }
}

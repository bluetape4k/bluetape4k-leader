package io.bluetape4k.leader.spring.route

import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.logging.KLogging
import java.time.Clock
import java.util.concurrent.CancellationException

/** route authority 평가를 cancellation/interruption과 분리하고 실패를 `Unavailable`로 정규화합니다.
 *
 * @property authority state 기반 또는 사용자 제공 route authority입니다.
 */
internal class LeaderRouteAuthorityRuntime(
    internal val authority: LeaderRouteAuthority,
    private val clock: Clock = Clock.systemUTC(),
) {

    private companion object: KLogging()

    /** Preserves the constructor descriptor published before redirect freshness support. */
    internal constructor(authority: LeaderRouteAuthority): this(authority, Clock.systemUTC())

    fun evaluate(slot: LeaderSlot): LeaderRouteDecision = evaluateSnapshot(slot).decision

    internal fun evaluateSnapshot(slot: LeaderSlot): LeaderRouteEvaluation {
        return try {
            when (val routeAuthority = authority) {
                is StateLeaderRouteAuthority -> routeAuthority.evaluateSnapshot(slot, clock)
                else -> evaluateCustom(routeAuthority, slot)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: Exception) {
            LeaderRouteEvaluation(LeaderRouteDecision.Unavailable, null, clock.instant())
        }
    }

    private fun evaluateCustom(
        routeAuthority: LeaderRouteAuthority,
        slot: LeaderSlot,
    ): LeaderRouteEvaluation = LeaderRouteEvaluation(
        decision = routeAuthority.evaluate(slot),
        leaderState = null,
        evaluatedAt = clock.instant(),
    )
}

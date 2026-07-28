package io.bluetape4k.leader.identity

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException

private val log = KotlinLogging.logger {}

/**
 * `safeNextLeaderId` 호출은 leader election 계약의 일부 동작을 수행합니다.
 *
 * 정상 contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @param provider `provider` 호출 또는 상태 계산에 필요한 값입니다.
 * @param lockName leader election에 사용할 lock 이름입니다. backend별 검증 규칙을 통과해야 하며 상태 조회와 audit의 기준 키가 됩니다.
 * @return 호출 결과입니다. leadership을 획득하지 못한 경우 null 또는 skip result가 될 수 있습니다.
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

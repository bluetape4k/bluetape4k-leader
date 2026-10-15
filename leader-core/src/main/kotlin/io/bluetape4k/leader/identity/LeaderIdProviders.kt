package io.bluetape4k.leader.identity

import io.bluetape4k.logging.KotlinLogging
import io.bluetape4k.logging.error
import io.bluetape4k.logging.warn
import kotlinx.coroutines.CancellationException

private val log = KotlinLogging.logger {}

/**
 * provider가 blank ID를 반환하거나 일반 예외를 던지면 random provider로 대체합니다.
 * `CancellationException`은 전파하고, `InterruptedException`은 interrupt 상태를 복원한 뒤 전파합니다.
 * @param provider 먼저 호출할 ID 공급자
 * @param lockName ID를 생성할 선거 대상 lock 이름
 * @return provider 또는 fallback이 생성한 leader ID
 */
@LeaderInternalApi
fun safeNextLeaderId(provider: LeaderIdProvider, lockName: String): String {
    return try {
        val result = provider.nextLeaderId(lockName)
        result.ifBlank {
            log.warn { "[safeNextLeaderId] Provider returned blank for lockName='$lockName', falling back to default." }
            RandomLeaderIdProvider.Default.nextLeaderId(lockName)
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

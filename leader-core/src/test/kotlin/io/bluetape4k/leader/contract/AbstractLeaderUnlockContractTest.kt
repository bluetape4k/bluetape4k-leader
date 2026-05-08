package io.bluetape4k.leader.contract

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.logging.KLogging
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeNull
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

/**
 * 6 백엔드 ([LeaderElector]) `runIfLeader` unlock 계약 회귀 베이스.
 *
 * ## 검증 계약 (CRITICAL — R-19, C-4)
 *
 * 모든 [LeaderElector] 구현체는 다음 두 가지를 보장해야 한다:
 *
 * 1. **본문 정상 종료 → 락 해제** — `runIfLeader` 본문이 정상적으로 값 또는 `null`을 반환한 후,
 *    동일 클라이언트가 즉시 동일 lockName 으로 재획득할 수 있어야 한다. lease 만료를 기다려서는
 *    안 된다.
 * 2. **본문 throw → 락 해제** — `runIfLeader` 본문이 예외를 throw 한 경우에도 try-finally 로
 *    unlock 이 보장되어야 한다. 예외 전파 후 동일 클라이언트가 즉시 재획득 가능해야 한다.
 *
 * ## 사용 방법
 *
 * 6 백엔드 (`Local`, `Lettuce`, `Redisson`, `Mongo` sync, `Hazelcast`, `ExposedJdbc`) 각각이
 * 본 클래스를 상속하여 [newElection]을 override 한다. testcontainers 가 필요한 백엔드는
 * `@Tag("integration")` + `XxxServer.Launcher.xxx` 표준 사용.
 *
 * ```kotlin
 * @Tag("integration")
 * class RedissonUnlockContractTest : AbstractLeaderUnlockContractTest() {
 *     companion object: KLogging() {
 *         val redis = RedisServer.Launcher.redis
 *     }
 *     override fun newElection(): LeaderElector = RedissonLeaderElector(client)
 * }
 * ```
 *
 * ## 범위 한계
 *
 * 본 베이스는 백엔드 unlock 계약만 검증한다. AOP `LeaderAspectFailureMode { RETHROW, SKIP }` 매트릭스
 * 검증은 Phase 5 `T5.12` (`*UnlockContractTest` Aspect 통합) 으로 위임된다.
 */
abstract class AbstractLeaderUnlockContractTest {

    companion object: KLogging() {
        private const val SAMPLE_RESULT = "ok"
        private val sampleException = RuntimeException("intentional body failure")
    }

    /**
     * 검증 대상 [LeaderElector] 인스턴스 생성.
     *
     * 호출마다 새 인스턴스를 반환해도 무방하지만, 동일 백엔드 (락 namespace) 를 가리켜야 한다.
     * 그렇지 않으면 두 인스턴스 간 락 격리가 잘못된 통과를 유발한다 (예: Local 의 정적 락 맵).
     */
    protected abstract fun newElection(): LeaderElector

    private fun randomLockName(): String = "unlock-${Base58.randomString(8)}"

    @Test
    fun `body normal return - 락이 즉시 해제되어 재획득 가능해야 한다`() {
        val election = newElection()
        val lockName = randomLockName()

        val first = election.runIfLeader(lockName) { SAMPLE_RESULT }
        first shouldBeEqualTo SAMPLE_RESULT

        // 즉시 재획득 — lease 만료 대기 없이
        val second = election.runIfLeader(lockName) { SAMPLE_RESULT }
        second.shouldNotBeNull()
        second shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `body throws - 예외 전파 후에도 락이 해제되어 재획득 가능해야 한다`() {
        val election = newElection()
        val lockName = randomLockName()

        assertFailsWith<RuntimeException> {
            election.runIfLeader<Unit>(lockName) {
                throw sampleException
            }
        }

        // 즉시 재획득 — try-finally unlock 보장
        val second = election.runIfLeader(lockName) { SAMPLE_RESULT }
        second.shouldNotBeNull()
        second shouldBeEqualTo SAMPLE_RESULT
    }

    @Test
    fun `body returns null - null 반환도 락 해제 후 재획득 가능해야 한다`() {
        val election = newElection()
        val lockName = randomLockName()

        val first = election.runIfLeader<String?>(lockName) { null }
        first.shouldBeNull()

        val second = election.runIfLeader(lockName) { SAMPLE_RESULT }
        second shouldBeEqualTo SAMPLE_RESULT
    }
}

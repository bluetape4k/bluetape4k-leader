package io.bluetape4k.leader

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class LeaderStateTest {

    @Test
    fun `empty - leader 없이 빈 상태를 생성한다`() {
        val state = LeaderState.empty("job-lock")

        state.status shouldBeEqualTo LeaderStatus.Empty
        state.isEmpty.shouldBeTrue()
        state.isOccupied.shouldBeFalse()
        state.leader shouldBeEqualTo null
    }

    @Test
    fun `occupied - leader lease 를 포함한 점유 상태를 생성한다`() {
        val lease = LeaderLease("node-a", electedAt = Instant.now())
        val state = LeaderState.occupied("job-lock", lease)

        state.status shouldBeEqualTo LeaderStatus.Occupied
        state.isOccupied.shouldBeTrue()
        state.leader shouldBeEqualTo lease
    }

    @Test
    fun `empty 상태에 leader 가 있으면 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderState("job-lock", LeaderStatus.Empty, LeaderLease("node-a"))
        }
    }

    @Test
    fun `occupied 상태에 leader 가 없으면 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderState("job-lock", LeaderStatus.Occupied)
        }
    }

    @Test
    fun `leaseUntil 이 electedAt 보다 이전이면 실패한다`() {
        val now = Instant.now()

        assertFailsWith<IllegalArgumentException> {
            LeaderLease("node-a", electedAt = now, leaseUntil = now.minusSeconds(1))
        }
    }
}

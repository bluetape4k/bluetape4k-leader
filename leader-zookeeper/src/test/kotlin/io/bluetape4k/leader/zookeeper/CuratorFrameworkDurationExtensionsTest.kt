package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.apache.curator.framework.CuratorFramework
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class CuratorFrameworkDurationExtensionsTest {

    private val curator = mockk<CuratorFramework>(relaxed = true)

    @BeforeEach
    fun beforeEach() {
        clearMocks(curator)
    }

    @Test
    fun `무한 timeout은 Curator의 무한 대기 overload를 사용하고 true를 반환한다`() {
        every { curator.blockUntilConnected() } just Runs

        curator.blockUntilConnected(Duration.INFINITE).shouldBeTrue()

        verify(exactly = 1) { curator.blockUntilConnected() }
        verify(exactly = 0) { curator.blockUntilConnected(any(), any()) }
    }

    @Test
    fun `zero timeout은 즉시 반환 계약을 millisecond overload로 전달한다`() {
        every { curator.blockUntilConnected(0, TimeUnit.MILLISECONDS) } returns false

        curator.blockUntilConnected(Duration.ZERO).shouldBeFalse()

        verify(exactly = 1) { curator.blockUntilConnected(0, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `negative timeout은 음수 대기값을 0 millisecond 즉시 반환으로 보존한다`() {
        every { curator.blockUntilConnected(0, TimeUnit.MILLISECONDS) } returns true

        curator.blockUntilConnected((-1).milliseconds).shouldBeTrue()

        verify(exactly = 1) { curator.blockUntilConnected(0, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `positive sub-millisecond timeout은 최소 1 millisecond로 올림한다`() {
        every { curator.blockUntilConnected(1, TimeUnit.MILLISECONDS) } returns true

        curator.blockUntilConnected(500.microseconds).shouldBeTrue()

        verify(exactly = 1) { curator.blockUntilConnected(1, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Int 최대 millisecond timeout은 그대로 전달한다`() {
        every { curator.blockUntilConnected(Int.MAX_VALUE, TimeUnit.MILLISECONDS) } returns true

        curator.blockUntilConnected(Int.MAX_VALUE.milliseconds).shouldBeTrue()

        verify(exactly = 1) { curator.blockUntilConnected(Int.MAX_VALUE, TimeUnit.MILLISECONDS) }
    }

    @Test
    fun `Int 범위를 초과한 유한 timeout은 호출 전에 거부한다`() {
        val timeout = (Int.MAX_VALUE.toLong() + 1).milliseconds

        val thrown = assertFailsWith<IllegalArgumentException> {
            curator.blockUntilConnected(timeout)
        }

        thrown.message shouldBeEqualTo "timeout must be <= ${Int.MAX_VALUE} milliseconds: $timeout"

        verify(exactly = 0) { curator.blockUntilConnected(any(), any()) }
        verify(exactly = 0) { curator.blockUntilConnected() }
    }
}

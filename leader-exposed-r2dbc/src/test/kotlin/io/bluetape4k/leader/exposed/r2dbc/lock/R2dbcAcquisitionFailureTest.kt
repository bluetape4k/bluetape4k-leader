package io.bluetape4k.leader.exposed.r2dbc.lock

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.exposed.retry.RetryStrategy
import io.bluetape4k.leader.exposed.r2dbc.internal.classifyAcquisitionFailure
import io.bluetape4k.leader.internal.BackendErrorKind
import io.mockk.coEvery
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.r2dbc.spi.R2dbcNonTransientResourceException
import io.r2dbc.spi.R2dbcTransientResourceException
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class R2dbcAcquisitionFailureTest {
    // 기본 인자는 transaction manager에서 평가되므로 실제 등록된 DB를 사용합니다.
    // transaction 본문은 mock 처리되어 SQL이나 연결 획득은 실행하지 않습니다.
    private val db = R2dbcDatabase.connect("r2dbc:h2:mem:///acquisition_failure")

    @BeforeEach
    fun mockTransactions() = mockkStatic("org.jetbrains.exposed.v1.r2dbc.transactions.TransactionsKt")

    @AfterEach
    fun restoreTransactions() = unmockkStatic("org.jetbrains.exposed.v1.r2dbc.transactions.TransactionsKt")

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fatal과 wrapped fatal은 경합이나 unavailable이 되지 않는다`(group: Boolean) = runSuspendIO {
        for (wrapped in listOf(false, true)) {
            val fatal = AssertionError("safe injected error")
            val thrown = if (wrapped) IllegalStateException("wrapper", fatal) else fatal
            coEvery { suspendTransaction<Boolean>(db, any(), any(), any()) } throws thrown
            val signals = mutableListOf<Boolean>()
            val actual = assertFailsWith<AssertionError> {
                acquire(group, signals)
            }
            (actual === fatal).shouldBeTrue()
            signals shouldBeEqualTo emptyList()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `wrapped cancellation도 원본 취소로 전파한다`(group: Boolean) = runSuspendIO {
        val cancelled = CancellationException("cancel")
        coEvery { suspendTransaction<Boolean>(db, any(), any(), any()) } throws IllegalStateException("wrapper", cancelled)
        val actual = assertFailsWith<CancellationException> { acquire(group, mutableListOf()) }
        (actual === cancelled).shouldBeTrue()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `non transient DB 오류는 한 번만 시도하고 기존 반환 정책을 유지한다`(group: Boolean) = runSuspendIO {
        val calls = AtomicInteger()
        val dbFailure = R2dbcNonTransientResourceException("invalid")
        coEvery { suspendTransaction<Boolean>(db, any(), any(), any()) } answers {
            calls.incrementAndGet()
            throw IllegalStateException("wrapper", dbFailure)
        }
        val signals = mutableListOf<Boolean>()
        acquire(group, signals) shouldBeEqualTo if (group) null else false
        calls.get() shouldBeEqualTo 1
        signals shouldBeEqualTo if (group) listOf(false) else emptyList()
    }

    @Test
    fun `single transient wrapper는 재시도 후 회복한다`() = runSuspendIO {
        val calls = AtomicInteger()
        coEvery { suspendTransaction<Boolean>(db, any(), any(), any()) } answers {
            if (calls.incrementAndGet() == 1) {
                throw IllegalStateException("wrapper", R2dbcTransientResourceException("retry"))
            }
            true
        }
        acquire(false, mutableListOf()).shouldBeTrue()
        calls.get() shouldBeEqualTo 2
    }

    @Test
    fun `group transient는 기존 unavailable 정책으로 순회를 중단한다`() = runSuspendIO {
        val calls = AtomicInteger()
        coEvery { suspendTransaction<Boolean>(db, any(), any(), any()) } answers {
            calls.incrementAndGet()
            throw R2dbcTransientResourceException("retry")
        }
        val signals = mutableListOf<Boolean>()
        acquire(true, signals) shouldBeEqualTo null
        calls.get() shouldBeEqualTo 1
        signals shouldBeEqualTo listOf(false)
    }

    @Test
    fun `single transient 재시도는 기존 monotonic 시간 예산을 소진하면 끝난다`() = runSuspendIO {
        val calls = AtomicInteger()
        coEvery { suspendTransaction<Boolean>(db, any(), any(), any()) } answers {
            calls.incrementAndGet()
            throw R2dbcTransientResourceException("retry")
        }
        val lock = ExposedR2dbcLock(db, "budget", RetryStrategy.Fixed(20))
        lock.tryLock(60.milliseconds, 1.seconds) shouldBeEqualTo false
        (calls.get() in 1..5).shouldBeTrue()
    }

    private suspend fun acquire(group: Boolean, signals: MutableList<Boolean>): Boolean? =
        if (group) {
            ExposedR2dbcGroupLock(db, "failure", 0, RetryStrategy.Fixed(10), useDbTime = true,
                onAvailabilityChanged = signals::add).tryLock(100.milliseconds, 1.seconds)
        } else {
            ExposedR2dbcLock(db, "failure", RetryStrategy.Fixed(10)).tryLock(100.milliseconds, 1.seconds)
        }

    @Test
    fun `중간 원인의 Error도 최하위 원인에 가려지지 않는다`() {
        val fatal = AssertionError("fatal", IllegalArgumentException("root"))
        val actual = assertFailsWith<AssertionError> {
            classifyAcquisitionFailure(IllegalStateException("wrapper", fatal))
        }
        (actual === fatal).shouldBeTrue()
    }

    @Test
    fun `cause 순환은 종료하고 unknown 예외는 재시도하지 않는다`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second", first)
        first.initCause(second)
        classifyAcquisitionFailure(first) shouldBeEqualTo BackendErrorKind.NON_TRANSIENT
    }

    @Test
    fun `transient backend 타입은 일반 root 원인보다 우선한다`() {
        val failure = R2dbcTransientResourceException("transient", IllegalStateException("root"))
        classifyAcquisitionFailure(IllegalStateException("wrapper", failure)) shouldBeEqualTo BackendErrorKind.TRANSIENT
    }
}

package io.bluetape4k.leader.examples.tenant

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TenantAggregatorRestartTest {
    @ParameterizedTest
    @ValueSource(strings = ["timeout", "caller-cancel", "direct-cancel", "zero-timeout"])
    fun `정리가 끝나기 전에는 재시작을 거부하고 완료 후 허용한다`(mode: String) = runTest {
        val release = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val elector = cleanupElector(cleaning, release)
        val worker = TenantAggregator({ _, _ -> elector }, TenantAggregatorOptions("node", listOf("tenant"))) { }
        val first = worker.start(backgroundScope)
        runCurrent()
        try {
            when (mode) {
                "timeout" -> worker.stopGracefully(10.milliseconds)
                "caller-cancel" -> {
                    val stopping = launch { worker.stopGracefully(Duration.INFINITE) }
                    runCurrent()
                    stopping.cancelAndJoin()
                }
                "direct-cancel" -> first.cancel()
                else -> worker.stopGracefully(Duration.ZERO)
            }
            runCurrent()
            cleaning.isCompleted.shouldBeTrue()
            first.isCompleted.shouldBeFalse()
            assertFailsWith<IllegalStateException> { worker.start(backgroundScope) }
        } finally {
            release.complete(Unit)
            first.cancelAndJoin()
        }
        val next = worker.start(backgroundScope)
        runCurrent()
        try {
            assertFailsWith<IllegalStateException> { worker.start(backgroundScope) }
        } finally {
            next.cancelAndJoin()
        }
    }

    @Test
    fun `이전 stop의 반환이 완료 callback에서 시작한 새 job을 지우지 않는다`() = runTest {
        val release = CompletableDeferred<Unit>()
        val elector = cleanupElector(CompletableDeferred(), release)
        val worker = TenantAggregator({ _, _ -> elector }, TenantAggregatorOptions("node", listOf("tenant"))) { }
        val first = worker.start(backgroundScope)
        runCurrent()
        var next: Job? = null
        first.invokeOnCompletion { next = worker.start(backgroundScope) }
        release.complete(Unit)
        worker.stopGracefully()
        runCurrent()
        try {
            requireNotNull(next).isActive.shouldBeTrue()
            assertFailsWith<IllegalStateException> { worker.start(backgroundScope) }
        } finally {
            next?.cancelAndJoin()
        }
    }

    @Test
    fun `이미 취소된 scope의 즉시 완료도 재시작을 막지 않는다`() = runTest {
        val release = CompletableDeferred<Unit>().apply { complete(Unit) }
        val elector = cleanupElector(CompletableDeferred(), release)
        val worker = TenantAggregator({ _, _ -> elector }, TenantAggregatorOptions("node", listOf("tenant"))) { }
        val cancelledScope = CoroutineScope(backgroundScope.coroutineContext + Job().apply { cancel() })
        val first = worker.start(cancelledScope)
        runCurrent()
        first.isCompleted.shouldBeTrue()
        val next = worker.start(backgroundScope)
        runCurrent()
        try {
            assertFailsWith<IllegalStateException> { worker.start(backgroundScope) }
        } finally {
            next.cancelAndJoin()
        }
    }

    /** 실제 worker의 job 종료를 backend cleanup 경계에서 멈춥니다. */
    private fun cleanupElector(
        cleaning: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ): SuspendLeaderElector = object: SuspendLeaderElector {
        override suspend fun <T> runIfLeader(lockName: String, action: suspend () -> T): T? {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleaning.complete(Unit)
                    release.await()
                }
            }
        }
    }
}

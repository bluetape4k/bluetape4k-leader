package io.bluetape4k.leader.k8s

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class KubernetesLeaseSuspendCancellationSafetyTest {

    @Test
    fun `suspend elector opens cleanup scope immediately after acquisition`() {
        val source = Path.of("src/main/kotlin/io/bluetape4k/leader/k8s/KubernetesLeaseSuspendLeaderElector.kt")
            .toFile()
            .readText()

        source.cleanupScopeStartsImmediatelyAfterAcquire().shouldBeTrue()
    }

    private fun String.cleanupScopeStartsImmediatelyAfterAcquire(): Boolean {
        val acquiredAt = indexOf("val acquiredAtNanos = System.nanoTime()")
        val cleanupScope = indexOf("var watchdog: AutoCloseable? = null", startIndex = acquiredAt.coerceAtLeast(0))
        val tryStart = indexOf("try {", startIndex = cleanupScope.coerceAtLeast(0))
        val watchdogStart = indexOf("LeaderLeaseAutoExtender.start", startIndex = tryStart.coerceAtLeast(0))
        val finallyStart = indexOf("} finally {", startIndex = watchdogStart.coerceAtLeast(0))
        val watchdogClose = indexOf("watchdog?.close()", startIndex = finallyStart.coerceAtLeast(0))
        val unlock = indexOf("lock.unlock(options.leaderOptions.minLeaseTime, acquiredAtNanos)", startIndex = watchdogClose.coerceAtLeast(0))
        return listOf(acquiredAt, cleanupScope, tryStart, watchdogStart, finallyStart, watchdogClose, unlock).all { it >= 0 } &&
            acquiredAt < cleanupScope &&
            cleanupScope < tryStart &&
            tryStart < watchdogStart &&
            watchdogStart < finallyStart &&
            finallyStart < watchdogClose &&
            watchdogClose < unlock
    }
}

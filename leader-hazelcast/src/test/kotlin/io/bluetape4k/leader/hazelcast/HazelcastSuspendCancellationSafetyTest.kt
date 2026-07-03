package io.bluetape4k.leader.hazelcast

import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class HazelcastSuspendCancellationSafetyTest {

    @Test
    fun `suspend elector unlock failure handling rethrows CancellationException`() {
        val source = sourceText("HazelcastSuspendLeaderElector.kt")

        source.rethrowsCancellationBeforeBroadCatch().shouldBeTrue()
        source.cleanupScopeStartsImmediatelyAfterAcquire().shouldBeTrue()
    }

    @Test
    fun `suspend group elector unlock failure handling rethrows CancellationException`() {
        val source = sourceText("HazelcastSuspendLeaderGroupElector.kt")

        source.rethrowsCancellationBeforeBroadCatch().shouldBeTrue()
    }

    private fun sourceText(fileName: String): String =
        Path.of("src/main/kotlin/io/bluetape4k/leader/hazelcast", fileName)
            .toFile()
            .readText()

    private fun String.rethrowsCancellationBeforeBroadCatch(): Boolean {
        val cancellationCatch = indexOf("catch (e: CancellationException) {\n                    throw e\n                }")
        val broadCatch = indexOf("catch (e: Exception)", startIndex = cancellationCatch.coerceAtLeast(0))
        return cancellationCatch >= 0 && broadCatch > cancellationCatch
    }

    private fun String.cleanupScopeStartsImmediatelyAfterAcquire(): Boolean {
        val acquiredAt = indexOf("val acquiredAtNanos = System.nanoTime()")
        val cleanupScope = indexOf("var watchdog: AutoCloseable? = null", startIndex = acquiredAt.coerceAtLeast(0))
        val tryStart = indexOf("try {", startIndex = cleanupScope.coerceAtLeast(0))
        val watchdogStart = indexOf("LeaderLeaseAutoExtender.start", startIndex = tryStart.coerceAtLeast(0))
        val finallyStart = indexOf("} finally {", startIndex = watchdogStart.coerceAtLeast(0))
        val watchdogClose = indexOf("watchdog?.close()", startIndex = finallyStart.coerceAtLeast(0))
        val unlock = indexOf("lock.unlock(options.minLeaseTime, acquiredAtNanos)", startIndex = watchdogClose.coerceAtLeast(0))
        return listOf(acquiredAt, cleanupScope, tryStart, watchdogStart, finallyStart, watchdogClose, unlock).all { it >= 0 } &&
            acquiredAt < cleanupScope &&
            cleanupScope < tryStart &&
            tryStart < watchdogStart &&
            watchdogStart < finallyStart &&
            finallyStart < watchdogClose &&
            watchdogClose < unlock
    }
}

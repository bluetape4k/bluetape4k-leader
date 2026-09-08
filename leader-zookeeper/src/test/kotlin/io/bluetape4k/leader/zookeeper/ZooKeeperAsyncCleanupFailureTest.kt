package io.bluetape4k.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.zookeeper.internal.ZooKeeperOwnedInterProcessMutex
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreV2
import org.apache.curator.framework.recipes.locks.Lease
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

class ZooKeeperAsyncCleanupFailureTest {

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `cleanup 실패에도 기존 best effort 및 원래 action 오류 정책을 유지한다`(group: Boolean) {
        val curator = mockk<CuratorFramework>(relaxed = true)
        val lease = mockk<Lease>()
        val cleanupFailure = IllegalStateException("cleanup failed")
        mockkConstructor(ZooKeeperOwnedInterProcessMutex::class, InterProcessSemaphoreV2::class)
        try {
            every { anyConstructed<ZooKeeperOwnedInterProcessMutex>().acquire(any(), any()) } returns true
            every { anyConstructed<ZooKeeperOwnedInterProcessMutex>().currentThreadLockPath() } returns "/leader/owned"
            every { anyConstructed<ZooKeeperOwnedInterProcessMutex>().release() } throws cleanupFailure
            every { anyConstructed<InterProcessSemaphoreV2>().acquire(any(), any()) } returns lease
            every { lease.nodeName } returns "lease-0"
            every { lease.close() } throws cleanupFailure
            val executor = Executor { it.run() }
            listOf(false, true).forEach { failAction ->
                val actionFailure = IllegalArgumentException("action failed")
                val action = {
                    if (failAction) CompletableFuture.failedFuture<String>(actionFailure)
                    else CompletableFuture.completedFuture("done")
                }
                val result = if (group) {
                    ZooKeeperLeaderGroupElector(curator, LeaderGroupElectionOptions(maxLeaders = 1))
                        .runAsyncIfLeader("job", executor, action)
                } else {
                    ZooKeeperLeaderElector(curator).runAsyncIfLeader("job", executor, action)
                }
                if (failAction) {
                    val failure = assertFailsWith<ExecutionException> { result.get(3, TimeUnit.SECONDS) }
                    (failure.cause === actionFailure).shouldBeTrue()
                } else {
                    result.get(3, TimeUnit.SECONDS) shouldBeEqualTo "done"
                }
            }
            if (group) verify(exactly = 2) { lease.close() }
            else verify(exactly = 2) { anyConstructed<ZooKeeperOwnedInterProcessMutex>().release() }
        } finally {
            unmockkConstructor(ZooKeeperOwnedInterProcessMutex::class, InterProcessSemaphoreV2::class)
        }
    }
}

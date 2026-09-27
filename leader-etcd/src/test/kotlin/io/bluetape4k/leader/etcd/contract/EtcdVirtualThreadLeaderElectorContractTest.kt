package io.bluetape4k.leader.etcd.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.await
import io.bluetape4k.concurrent.completableFutureOf
import io.bluetape4k.concurrent.virtualthread.VirtualThreadExecutor
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.leader.LeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderElector
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElectionOptions
import io.bluetape4k.leader.etcd.EtcdLeaderGroupElector
import io.bluetape4k.leader.etcd.EtcdVirtualThreadLeaderElector
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds

/**
 * Direct etcd virtual-thread and group executor overload coverage.
 *
 * etcd exposes a lock-name virtual wrapper and a group executor overload; it does
 * not expose a concrete async slot overload, so slot-aware async coverage is N/A.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EtcdVirtualThreadLeaderElectorContractTest {

    companion object: KLogging()

    @Test
    fun virtualWrapperAcquiresAndReleasesUsingLockName() {
        val elector = EtcdVirtualThreadLeaderElector(
            EtcdLeaderElector(
                EtcdContractSupport.client,
                EtcdLeaderElectionOptions(keyPrefix = EtcdContractSupport.keyPrefix()),
            ),
        )
        val lockName = "etcd-virtual-contract"

        elector.runAsyncIfLeader(lockName) { "virtual-ok" }.await() shouldBeEqualTo "virtual-ok"
        elector.runAsyncIfLeader(lockName) { "virtual-reacquired" }.await() shouldBeEqualTo "virtual-reacquired"
    }

    @Test
    fun virtualWrapperCancellationInterruptsActionAndReleasesLock() {
        val elector = EtcdVirtualThreadLeaderElector(
            EtcdLeaderElector(
                EtcdContractSupport.client,
                EtcdLeaderElectionOptions(keyPrefix = EtcdContractSupport.keyPrefix()),
            ),
        )
        val lockName = "etcd-virtual-cancellation-contract"
        val actionStarted = CountDownLatch(1)
        val actionInterrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = elector.runAsyncIfLeader(lockName) {
            actionStarted.countDown()
            try {
                release.await()
                "should-not-complete"
            } catch (e: InterruptedException) {
                actionInterrupted.countDown()
                throw e
            }
        }

        try {
            actionStarted.await(10.seconds).shouldBeTrue()
            result.cancel(true).shouldBeTrue()
            actionInterrupted.await(10.seconds).shouldBeTrue()

            elector.runAsyncIfLeader(lockName) {
                "virtual-reacquired"
            }.await(10.seconds()) shouldBeEqualTo "virtual-reacquired"
        } finally {
            release.countDown()
        }
    }

    @Test
    fun groupExecutorOverloadAcquiresAndReleases() {
        val elector = EtcdLeaderGroupElector(
            EtcdContractSupport.client,
            EtcdLeaderGroupElectionOptions(
                leaderGroupOptions = LeaderGroupElectionOptions(maxLeaders = 2),
                keyPrefix = EtcdContractSupport.keyPrefix(),
            ),
        )
        val lockName = "etcd-group-executor-contract"
        val future = elector.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            completableFutureOf("group-executor-ok")
        }

        future.join() shouldBeEqualTo "group-executor-ok"

        elector.runAsyncIfLeader(lockName, VirtualThreadExecutor) {
            completableFutureOf("group-executor-reacquired")
        }.join() shouldBeEqualTo "group-executor-reacquired"
    }
}

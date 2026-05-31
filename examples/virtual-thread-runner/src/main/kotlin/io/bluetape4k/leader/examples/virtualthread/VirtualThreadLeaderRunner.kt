package io.bluetape4k.leader.examples.virtualthread

import io.bluetape4k.concurrent.virtualthread.VirtualFuture
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.VirtualThreadLeaderElector
import io.bluetape4k.leader.local.LocalVirtualThreadLeaderElector
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotEmpty
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * High-concurrency runner guarded by [VirtualThreadLeaderElector].
 *
 * ## Contract
 *
 * All nodes race for the same [lockName]. Exactly one node executes the
 * leader-only action on a Java virtual thread; every loser returns a skipped
 * report without throwing. The runner keeps the leader action bounded with a
 * latch timeout so demos and tests shut down predictably.
 */
class VirtualThreadLeaderRunner(
    private val lockName: String,
    private val elector: VirtualThreadLeaderElector = LocalVirtualThreadLeaderElector(
        LeaderElectionOptions(
            waitTime = Duration.ZERO,
            leaseTime = 30.seconds,
        ),
    ),
    private val leaderHoldTimeout: Duration = 5.seconds,
) {

    init {
        lockName.requireNotBlank("lockName")
        leaderHoldTimeout.inWholeMilliseconds.requirePositiveNumber("leaderHoldTimeout")
    }

    companion object: KLogging() {
        fun defaultNodeIds(count: Int = 64): List<String> {
            count.requirePositiveNumber("count")
            return (1..count).map { "node-$it" }
        }
    }

    fun runRound(
        nodeIds: List<String> = defaultNodeIds(),
    ): VirtualThreadRunReport {
        nodeIds.requireNotEmpty("nodeIds")
        nodeIds.forEach { it.requireNotBlank("nodeId") }

        val leaderNodeId = nodeIds.first()
        val contenderNodeIds = nodeIds.drop(1)
        val leaderStarted = CountDownLatch(1)
        val releaseLeader = CountDownLatch(1)
        val leaderFuture = runLeaderAction(leaderNodeId, leaderStarted, releaseLeader)

        check(leaderStarted.await(leaderHoldTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            "No node acquired leadership within $leaderHoldTimeout"
        }
        val contenderReports = contenderNodeIds.map { nodeId ->
            val report = elector.runAsyncIfLeader(lockName) {
                val thread = Thread.currentThread()
                log.info { "[$nodeId] unexpectedly elected on virtualThread=${thread.isVirtual}" }
                VirtualThreadNodeReport(
                    nodeId = nodeId,
                    status = VirtualThreadNodeStatus.ELECTED,
                    ranOnVirtualThread = thread.isVirtual,
                    threadName = thread.name,
                )
            }.await()

            report ?: VirtualThreadNodeReport(
                nodeId = nodeId,
                status = VirtualThreadNodeStatus.SKIPPED,
                ranOnVirtualThread = false,
            )
        }
        releaseLeader.countDown()
        val leaderReport = leaderFuture.await()
            ?: error("Leader future completed without an elected report")

        return VirtualThreadRunReport(
            lockName = lockName,
            nodeReports = listOf(leaderReport) + contenderReports,
        )
    }

    private fun runLeaderAction(
        nodeId: String,
        leaderStarted: CountDownLatch,
        releaseLeader: CountDownLatch,
    ): VirtualFuture<VirtualThreadNodeReport?> =
        elector.runAsyncIfLeader(lockName) {
            val thread = Thread.currentThread()
            leaderStarted.countDown()
            releaseLeader.await(leaderHoldTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            log.info { "[$nodeId] elected on virtualThread=${thread.isVirtual}" }
            VirtualThreadNodeReport(
                nodeId = nodeId,
                status = VirtualThreadNodeStatus.ELECTED,
                ranOnVirtualThread = thread.isVirtual,
                threadName = thread.name,
            )
        }
}

enum class VirtualThreadNodeStatus {
    ELECTED,
    SKIPPED,
}

data class VirtualThreadNodeReport(
    val nodeId: String,
    val status: VirtualThreadNodeStatus,
    val ranOnVirtualThread: Boolean,
    val threadName: String? = null,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 6477254429103328412L
    }
}

data class VirtualThreadRunReport(
    val lockName: String,
    val nodeReports: List<VirtualThreadNodeReport>,
): Serializable {

    val electedCount: Int
        get() = nodeReports.count { it.status == VirtualThreadNodeStatus.ELECTED }

    val skippedCount: Int
        get() = nodeReports.count { it.status == VirtualThreadNodeStatus.SKIPPED }

    val electedNodeId: String?
        get() = nodeReports.singleOrNull { it.status == VirtualThreadNodeStatus.ELECTED }?.nodeId

    companion object {
        private const val serialVersionUID: Long = 8172952800981461768L
    }
}

package io.bluetape4k.leader.contract

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeInstanceOf
import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.AsyncLeaderElector
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderRunResult
import io.bluetape4k.leader.LeaderSlot
import io.bluetape4k.leader.identity.LeaderElectorBridgeLog
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture

/**
 * Backend-agnostic contract for [AsyncLeaderElector] slot-aware audit identity propagation.
 *
 * ## Verified contracts
 * - `runAsyncIfLeaderResult(slot)` -> [LeaderRunResult.Elected.leaderId] == `slot.leaderId`
 * - Correct async slot overrides do not trigger [LeaderElectorBridgeLog]
 *
 * Subclasses implement [createElector] to supply the backend-specific [AsyncLeaderElector].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractAsyncLeaderElectorLeaderIdContractTest {

    companion object : KLogging()

    protected abstract fun createElector(options: LeaderElectionOptions): AsyncLeaderElector

    private val defaultElector: AsyncLeaderElector by lazy {
        createElector(LeaderElectionOptions.Default)
    }

    @BeforeEach
    fun resetBridgeLog() {
        LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())
    }

    private fun slot(leaderId: String = "node-a") =
        LeaderSlot("lock-${Base58.randomString(8)}", leaderId)

    @Test
    fun `runAsyncIfLeaderResult(slot) - Elected 반환 및 leaderId 전파`() {
        val s = slot("async-audit-node")
        val result = defaultElector.runAsyncIfLeaderResult(s) {
            CompletableFuture.completedFuture("done")
        }.join()

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).value shouldBeEqualTo "done"
        result.leaderId shouldBeEqualTo "async-audit-node"
    }

    @Test
    fun `runAsyncIfLeaderResult(slot) - action null 반환해도 Elected with leaderId`() {
        val s = slot("async-null-node")
        val result = defaultElector.runAsyncIfLeaderResult<String?>(s) {
            CompletableFuture.completedFuture(null)
        }.join()

        result shouldBeInstanceOf LeaderRunResult.Elected::class
        (result as LeaderRunResult.Elected).leaderId shouldBeEqualTo "async-null-node"
    }

    @Test
    fun `runAsyncIfLeader(slot) - bridge log 미호출`() {
        defaultElector.runAsyncIfLeader(slot()) {
            CompletableFuture.completedFuture(Unit)
        }.join()

        LeaderElectorBridgeLog.global().droppedAuditCount() shouldBeEqualTo 0L
    }

    @Test
    fun `runAsyncIfLeaderResult(slot) - result bridge log 미호출`() {
        defaultElector.runAsyncIfLeaderResult(slot()) {
            CompletableFuture.completedFuture(Unit)
        }.join()

        LeaderElectorBridgeLog.global().droppedResultBridgeCount() shouldBeEqualTo 0L
    }
}

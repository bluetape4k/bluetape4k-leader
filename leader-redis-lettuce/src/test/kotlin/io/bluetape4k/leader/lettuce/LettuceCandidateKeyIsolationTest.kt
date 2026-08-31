package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import org.junit.jupiter.api.Test
import kotlin.time.Duration

class LettuceCandidateKeyIsolationTest : AbstractLettuceLeaderTest() {

    @Test
    fun `blocking candidate lifecycle preserves lock and node boundaries`() {
        val (lockName, nodeId, collidingLockName, collidingNodeId) = collisionPair()
        val elector = LettuceStrategicLeaderElector(connection, "observer")

        elector.registerCandidate(lockName, CandidateInfo(nodeId, metadata = mapOf("owner" to "first")))
        elector.registerCandidate(
            collidingLockName,
            CandidateInfo(collidingNodeId, metadata = mapOf("owner" to "second")),
        )

        elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
        elector.listCandidates(collidingLockName).single().nodeId shouldBeEqualTo collidingNodeId

        elector.refreshCandidate(
            lockName,
            CandidateInfo(nodeId, metadata = mapOf("owner" to "first-refreshed")),
            Duration.ZERO,
        )
        elector.listCandidates(lockName).single().metadata shouldBeEqualTo mapOf("owner" to "first-refreshed")
        elector.listCandidates(collidingLockName).single().metadata shouldBeEqualTo mapOf("owner" to "second")

        elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)
        elector.listCandidates(lockName).single().successCount shouldBeEqualTo 1L
        elector.listCandidates(collidingLockName).single().successCount shouldBeEqualTo 0L

        elector.unregisterCandidate(lockName, nodeId)
        elector.listCandidates(lockName).shouldBeEmpty()
        elector.listCandidates(collidingLockName).single().nodeId shouldBeEqualTo collidingNodeId
    }

    @Test
    fun `suspend candidate lifecycle preserves lock and node boundaries`() = runSuspendIO {
        val (lockName, nodeId, collidingLockName, collidingNodeId) = collisionPair()
        val elector = LettuceStrategicSuspendLeaderElector(connection, "observer")

        elector.registerCandidate(lockName, CandidateInfo(nodeId, metadata = mapOf("owner" to "first")))
        elector.registerCandidate(
            collidingLockName,
            CandidateInfo(collidingNodeId, metadata = mapOf("owner" to "second")),
        )

        elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
        elector.listCandidates(collidingLockName).single().nodeId shouldBeEqualTo collidingNodeId

        elector.refreshCandidate(
            lockName,
            CandidateInfo(nodeId, metadata = mapOf("owner" to "first-refreshed")),
            Duration.ZERO,
        )
        elector.listCandidates(lockName).single().metadata shouldBeEqualTo mapOf("owner" to "first-refreshed")
        elector.listCandidates(collidingLockName).single().metadata shouldBeEqualTo mapOf("owner" to "second")

        elector.updateResult(lockName, nodeId, CandidateResult.SUCCESS)
        elector.listCandidates(lockName).single().successCount shouldBeEqualTo 1L
        elector.listCandidates(collidingLockName).single().successCount shouldBeEqualTo 0L

        elector.unregisterCandidate(lockName, nodeId)
        elector.listCandidates(lockName).shouldBeEmpty()
        elector.listCandidates(collidingLockName).single().nodeId shouldBeEqualTo collidingNodeId
    }

    @Test
    fun `legacy candidate with exact node id is read through and migrated`() {
        val lockName = "issue-845-legacy-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        val candidate = CandidateInfo(nodeId, metadata = mapOf("source" to "legacy"))
        val legacyIndexKey = legacyIndexKey(lockName)
        val legacyCandidateKey = legacyCandidateKey(lockName, nodeId)

        connection.sync().set(legacyCandidateKey, LettuceCandidateInfoCodec.encode(candidate))
        connection.sync().sadd(legacyIndexKey, nodeId)

        val listed = LettuceStrategicLeaderElector(connection, "observer").listCandidates(lockName)

        listed.single().nodeId shouldBeEqualTo nodeId
        connection.sync().get(versionedCandidateKey(lockName, nodeId)).shouldNotBeNull()
    }

    @Test
    fun `legacy candidate with a different node id is never exposed`() {
        val lockName = "issue-845-legacy-mismatch-${System.nanoTime()}"
        val expectedNodeId = "hostname:pid"
        val actualNodeId = "other-node"
        val legacyIndexKey = legacyIndexKey(lockName)
        val legacyCandidateKey = legacyCandidateKey(lockName, expectedNodeId)

        connection.sync().set(
            legacyCandidateKey,
            LettuceCandidateInfoCodec.encode(CandidateInfo(actualNodeId)),
        )
        connection.sync().sadd(legacyIndexKey, expectedNodeId)

        LettuceStrategicLeaderElector(connection, "observer")
            .listCandidates(lockName)
            .shouldBeEmpty()
    }

    @Test
    fun `blocking lifecycle operations migrate an exact legacy candidate before mutation`() {
        val nodeId = "hostname:pid"
        val elector = LettuceStrategicLeaderElector(connection, "observer")

        val refreshLock = "issue-845-legacy-refresh-${System.nanoTime()}"
        seedLegacy(refreshLock, CandidateInfo(nodeId, metadata = mapOf("source" to "old")))
        elector.refreshCandidate(
            refreshLock,
            CandidateInfo(nodeId, metadata = mapOf("source" to "fresh")),
            Duration.ZERO,
        )
        elector.listCandidates(refreshLock).single().metadata shouldBeEqualTo mapOf("source" to "fresh")

        val updateLock = "issue-845-legacy-update-${System.nanoTime()}"
        seedLegacy(updateLock, CandidateInfo(nodeId))
        elector.updateResult(updateLock, nodeId, CandidateResult.SUCCESS)
        elector.listCandidates(updateLock).single().successCount shouldBeEqualTo 1L

        val unregisterLock = "issue-845-legacy-unregister-${System.nanoTime()}"
        seedLegacy(unregisterLock, CandidateInfo(nodeId))
        elector.unregisterCandidate(unregisterLock, nodeId)
        elector.listCandidates(unregisterLock).shouldBeEmpty()
        connection.sync().get(legacyCandidateKey(unregisterLock, nodeId)).shouldBeNull()
    }

    @Test
    fun `suspend lifecycle operations migrate an exact legacy candidate before mutation`() = runSuspendIO {
        val nodeId = "hostname:pid"
        val elector = LettuceStrategicSuspendLeaderElector(connection, "observer")

        val refreshLock = "issue-845-suspend-legacy-refresh-${System.nanoTime()}"
        seedLegacy(refreshLock, CandidateInfo(nodeId, metadata = mapOf("source" to "old")))
        elector.refreshCandidate(
            refreshLock,
            CandidateInfo(nodeId, metadata = mapOf("source" to "fresh")),
            Duration.ZERO,
        )
        elector.listCandidates(refreshLock).single().metadata shouldBeEqualTo mapOf("source" to "fresh")

        val updateLock = "issue-845-suspend-legacy-update-${System.nanoTime()}"
        seedLegacy(updateLock, CandidateInfo(nodeId))
        elector.updateResult(updateLock, nodeId, CandidateResult.SUCCESS)
        elector.listCandidates(updateLock).single().successCount shouldBeEqualTo 1L

        val unregisterLock = "issue-845-suspend-legacy-unregister-${System.nanoTime()}"
        seedLegacy(unregisterLock, CandidateInfo(nodeId))
        elector.unregisterCandidate(unregisterLock, nodeId)
        elector.listCandidates(unregisterLock).shouldBeEmpty()
        connection.sync().get(legacyCandidateKey(unregisterLock, nodeId)).shouldBeNull()
    }

    @Test
    fun `version-shaped lock name remains separate from the legacy namespace`() {
        val lockName = "v2:c:1:a1:b"
        val nodeId = "hostname:pid"
        val elector = LettuceStrategicLeaderElector(connection, "observer")

        connection.sync().sadd(legacyIndexKey(lockName), "legacy-node")
        elector.registerCandidate(lockName, CandidateInfo(nodeId))

        connection.sync().type(legacyIndexKey(lockName)) shouldBeEqualTo "set"
        elector.listCandidates(lockName).single().nodeId shouldBeEqualTo nodeId
        connection.sync().get(versionedCandidateKey(lockName, nodeId)).shouldNotBeNull()
    }

    private fun collisionPair(): CollisionPair {
        val suffix = System.nanoTime().toString()
        val lockName = "issue-845-$suffix"
        val nodeId = "hostname:pid"
        return CollisionPair(
            lockName = lockName,
            nodeId = nodeId,
            collidingLockName = "$lockName:hostname",
            collidingNodeId = "pid",
        )
    }

    private fun legacyIndexKey(lockName: String) =
        "${LettuceCandidateRegistry.DEFAULT_KEY_PREFIX}:$lockName"

    private fun legacyCandidateKey(lockName: String, nodeId: String) =
        "${legacyIndexKey(lockName)}:$nodeId"

    private fun seedLegacy(lockName: String, candidate: CandidateInfo) {
        connection.sync().set(
            legacyCandidateKey(lockName, candidate.nodeId),
            LettuceCandidateInfoCodec.encode(candidate),
        )
        connection.sync().sadd(legacyIndexKey(lockName), candidate.nodeId)
    }

    private fun versionedCandidateKey(lockName: String, nodeId: String): String {
        fun part(value: String) = "${value.toByteArray(Charsets.UTF_8).size}:$value"
        return "${LettuceCandidateRegistry.DEFAULT_KEY_PREFIX}|v2|c|${part(lockName)}${part(nodeId)}"
    }

    private data class CollisionPair(
        val lockName: String,
        val nodeId: String,
        val collidingLockName: String,
        val collidingNodeId: String,
    )
}

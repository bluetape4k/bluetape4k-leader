package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.assertFailsWith
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
        connection.sync().get(currentCandidateKey(lockName, nodeId)).shouldNotBeNull()
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
    fun `malformed v3 destination is surfaced instead of hiding a valid legacy source`() {
        val lockName = "issue-854-malformed-v3-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        val v3Key = currentCandidateKey(lockName, nodeId)
        val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
            nodeId,
        )
        val v2Index = LettuceCandidateKeyCodec.v2IndexKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
        )
        val raw = LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId, metadata = mapOf("source" to "v2")))
        connection.sync().set(v3Key, "malformed-v3-payload")
        connection.sync().set(v2Key, raw)
        connection.sync().sadd(v2Index, nodeId)

        assertFailsWith<IllegalArgumentException> {
            LettuceStrategicLeaderElector(connection, "observer").listCandidates(lockName)
        }
        connection.sync().get(v3Key) shouldBeEqualTo "malformed-v3-payload"
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
        connection.sync().get(currentCandidateKey(lockName, nodeId)).shouldNotBeNull()
    }

    @Test
    fun `blocking list removes a stale v2 index while preserving the migrated colon source`() {
        val lockName = "issue-845-stale-migration-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        seedLegacy(lockName, CandidateInfo(nodeId, metadata = mapOf("source" to "legacy")))
        connection.sync().sadd(versionedIndexKey(lockName), nodeId)

        LettuceStrategicLeaderElector(connection, "observer").listCandidates(lockName)

        connection.sync().smembers(versionedIndexKey(lockName)).shouldBeEmpty()
        connection.sync().get(currentCandidateKey(lockName, nodeId)).shouldNotBeNull()
    }

    @Test
    fun `suspend list removes a stale v2 index while preserving the migrated colon source`() = runSuspendIO {
        val lockName = "issue-845-suspend-stale-migration-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        seedLegacy(lockName, CandidateInfo(nodeId, metadata = mapOf("source" to "legacy")))
        connection.sync().sadd(versionedIndexKey(lockName), nodeId)

        LettuceStrategicSuspendLeaderElector(connection, "observer").listCandidates(lockName)

        connection.sync().smembers(versionedIndexKey(lockName)).shouldBeEmpty()
        connection.sync().get(currentCandidateKey(lockName, nodeId)).shouldNotBeNull()
    }

    @Test
    fun `blocking list prefers an existing v2 value while promoting the v3 index`() {
        val lockName = "issue-845-v2-precedence-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        seedLegacy(lockName, CandidateInfo(nodeId, metadata = mapOf("source" to "legacy")))
        connection.sync().set(
            LettuceCandidateKeyCodec.v2CandidateKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName, nodeId),
            LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId, metadata = mapOf("source" to "v2"))),
        )

        val listed = LettuceStrategicLeaderElector(connection, "observer").listCandidates(lockName)

        listed.single().metadata shouldBeEqualTo mapOf("source" to "v2")
        connection.sync().smembers(currentIndexKey(lockName)) shouldBeEqualTo setOf(nodeId)
    }

    @Test
    fun `suspend list prefers an existing v2 value while promoting the v3 index`() = runSuspendIO {
        val lockName = "issue-845-suspend-v2-precedence-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        seedLegacy(lockName, CandidateInfo(nodeId, metadata = mapOf("source" to "legacy")))
        connection.sync().set(
            LettuceCandidateKeyCodec.v2CandidateKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName, nodeId),
            LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId, metadata = mapOf("source" to "v2"))),
        )

        val listed = LettuceStrategicSuspendLeaderElector(connection, "observer").listCandidates(lockName)

        listed.single().metadata shouldBeEqualTo mapOf("source" to "v2")
        connection.sync().smembers(currentIndexKey(lockName)) shouldBeEqualTo setOf(nodeId)
    }

    @Test
    fun `blocking list removes only the stale legacy version index`() {
        val lockName = "issue-854-stale-version-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        val currentRaw = LettuceCandidateInfoCodec.encode(
            CandidateInfo(nodeId, metadata = mapOf("source" to "v3")),
        )
        val v2Raw = LettuceCandidateInfoCodec.encode(
            CandidateInfo(nodeId, metadata = mapOf("source" to "v2")),
        )
        connection.sync().set(currentCandidateKey(lockName, nodeId), currentRaw)
        connection.sync().sadd(currentIndexKey(lockName), nodeId)
        connection.sync().set(
            LettuceCandidateKeyCodec.v2CandidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            ),
            v2Raw,
        )
        connection.sync().sadd(versionedIndexKey(lockName), nodeId)
        connection.sync().sadd(legacyIndexKey(lockName), nodeId)

        LettuceStrategicLeaderElector(connection, "observer").listCandidates(lockName)

        connection.sync().smembers(versionedIndexKey(lockName)) shouldBeEqualTo setOf(nodeId)
        connection.sync().sismember(legacyIndexKey(lockName), nodeId) shouldBeEqualTo false
    }

    @Test
    fun `suspend list removes only the stale legacy version index`() = runSuspendIO {
        val lockName = "issue-854-suspend-stale-version-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        val currentRaw = LettuceCandidateInfoCodec.encode(
            CandidateInfo(nodeId, metadata = mapOf("source" to "v3")),
        )
        val v2Raw = LettuceCandidateInfoCodec.encode(
            CandidateInfo(nodeId, metadata = mapOf("source" to "v2")),
        )
        connection.sync().set(currentCandidateKey(lockName, nodeId), currentRaw)
        connection.sync().sadd(currentIndexKey(lockName), nodeId)
        connection.sync().set(
            LettuceCandidateKeyCodec.v2CandidateKey(
                LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
                lockName,
                nodeId,
            ),
            v2Raw,
        )
        connection.sync().sadd(versionedIndexKey(lockName), nodeId)
        connection.sync().sadd(legacyIndexKey(lockName), nodeId)

        LettuceStrategicSuspendLeaderElector(connection, "observer").listCandidates(lockName)

        connection.sync().smembers(versionedIndexKey(lockName)) shouldBeEqualTo setOf(nodeId)
        connection.sync().sismember(legacyIndexKey(lockName), nodeId) shouldBeEqualTo false
    }

    @Test
    fun `blocking list surfaces malformed legacy source even when v3 is current`() {
        val lockName = "issue-854-malformed-legacy-current-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        connection.sync().set(currentCandidateKey(lockName, nodeId), LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId)))
        connection.sync().sadd(currentIndexKey(lockName), nodeId)
        val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
            nodeId,
        )
        connection.sync().set(v2Key, LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId)))
        connection.sync().sadd(
            LettuceCandidateKeyCodec.v2IndexKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName),
            nodeId,
        )
        connection.sync().set(legacyCandidateKey(lockName, nodeId), "malformed-legacy-payload")
        connection.sync().sadd(legacyIndexKey(lockName), nodeId)

        assertFailsWith<IllegalArgumentException> {
            LettuceStrategicLeaderElector(connection, "observer").listCandidates(lockName)
        }
    }

    @Test
    fun `suspend list surfaces malformed legacy source even when v3 is current`() = runSuspendIO {
        val lockName = "issue-854-suspend-malformed-legacy-current-${System.nanoTime()}"
        val nodeId = "hostname:pid"
        connection.sync().set(currentCandidateKey(lockName, nodeId), LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId)))
        connection.sync().sadd(currentIndexKey(lockName), nodeId)
        val v2Key = LettuceCandidateKeyCodec.v2CandidateKey(
            LettuceCandidateRegistry.DEFAULT_KEY_PREFIX,
            lockName,
            nodeId,
        )
        connection.sync().set(v2Key, LettuceCandidateInfoCodec.encode(CandidateInfo(nodeId)))
        connection.sync().sadd(
            LettuceCandidateKeyCodec.v2IndexKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName),
            nodeId,
        )
        connection.sync().set(legacyCandidateKey(lockName, nodeId), "malformed-legacy-payload")
        connection.sync().sadd(legacyIndexKey(lockName), nodeId)

        assertFailsWith<IllegalArgumentException> {
            LettuceStrategicSuspendLeaderElector(connection, "observer").listCandidates(lockName)
        }
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

    private fun currentCandidateKey(lockName: String, nodeId: String) =
        LettuceCandidateKeyCodec.candidateKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName, nodeId)

    private fun currentIndexKey(lockName: String) =
        LettuceCandidateKeyCodec.indexKey(LettuceCandidateRegistry.DEFAULT_KEY_PREFIX, lockName)

    private fun versionedIndexKey(lockName: String) =
        "${LettuceCandidateRegistry.DEFAULT_KEY_PREFIX}|v2|i|${lockName.toByteArray(Charsets.UTF_8).size}:$lockName"

    private data class CollisionPair(
        val lockName: String,
        val nodeId: String,
        val collidingLockName: String,
        val collidingNodeId: String,
    )
}

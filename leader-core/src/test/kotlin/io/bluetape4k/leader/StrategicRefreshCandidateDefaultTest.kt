package io.bluetape4k.leader

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderElector
import io.bluetape4k.leader.coroutines.StrategicSuspendLeaderGroupElector
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.bluetape4k.leader.strategy.ElectionStrategy
import io.bluetape4k.leader.strategy.GroupElectionStrategy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class StrategicRefreshCandidateDefaultTest {

    @Test
    fun `blocking single default refresh는 missing expired 후보를 부활시키지 않고 기존 상태를 보존한다`() {
        val fixture = BlockingSingleFixture()
        assertBlockingRefreshContract(
            fixture = fixture,
            register = { lockName, info, ttl -> fixture.registerCandidate(lockName, info, ttl) },
            refresh = { lockName, info, ttl -> fixture.refreshCandidate(lockName, info, ttl) },
            list = { lockName -> fixture.listCandidates(lockName) },
        )
    }

    @Test
    fun `blocking group default refresh는 missing expired 후보를 부활시키지 않고 기존 상태를 보존한다`() {
        val fixture = BlockingGroupFixture()
        assertBlockingRefreshContract(
            fixture = fixture,
            register = { lockName, info, ttl -> fixture.registerCandidate(lockName, info, ttl) },
            refresh = { lockName, info, ttl -> fixture.refreshCandidate(lockName, info, ttl) },
            list = { lockName -> fixture.listCandidates(lockName) },
        )
    }

    @Test
    fun `suspend single default refresh는 missing expired 후보를 부활시키지 않고 기존 상태를 보존한다`() = runTest {
        val fixture = SuspendSingleFixture()
        assertSuspendRefreshContract(
            fixture = fixture,
            register = { lockName, info, ttl -> fixture.registerCandidate(lockName, info, ttl) },
            refresh = { lockName, info, ttl -> fixture.refreshCandidate(lockName, info, ttl) },
            list = { lockName -> fixture.listCandidates(lockName) },
        )
    }

    @Test
    fun `suspend group default refresh는 missing expired 후보를 부활시키지 않고 기존 상태를 보존한다`() = runTest {
        val fixture = SuspendGroupFixture()
        assertSuspendRefreshContract(
            fixture = fixture,
            register = { lockName, info, ttl -> fixture.registerCandidate(lockName, info, ttl) },
            refresh = { lockName, info, ttl -> fixture.refreshCandidate(lockName, info, ttl) },
            list = { lockName -> fixture.listCandidates(lockName) },
        )
    }

    private fun assertBlockingRefreshContract(
        fixture: BlockingFixture,
        register: (String, CandidateInfo, Duration) -> Unit,
        refresh: (String, CandidateInfo, Duration) -> Unit,
        list: (String) -> List<CandidateInfo>,
    ) {
        refresh(
            MISSING_LOCK,
            CandidateInfo("missing", metadata = mapOf("heartbeat" to "missing")),
            REFRESH_TTL,
        )
        list(MISSING_LOCK).shouldBeEmpty()
        fixture.registrationCount shouldBeEqualTo 0

        register(EXPIRED_LOCK, CandidateInfo("expired"), INITIAL_TTL)
        fixture.expireCandidate(EXPIRED_LOCK, "expired")
        list(EXPIRED_LOCK).shouldBeEmpty()
        val registrationsBeforeRefresh = fixture.registrationCount

        refresh(
            EXPIRED_LOCK,
            CandidateInfo("expired", metadata = mapOf("heartbeat" to "expired")),
            REFRESH_TTL,
        )

        list(EXPIRED_LOCK).shouldBeEmpty()
        fixture.registrationCount shouldBeEqualTo registrationsBeforeRefresh

        val original = candidateInfo(fixture.nodeId)
        register(PRESERVE_LOCK, original, INITIAL_TTL)

        refresh(
            PRESERVE_LOCK,
            CandidateInfo(fixture.nodeId, metadata = NEW_METADATA),
            REFRESH_TTL,
        )

        val refreshed = list(PRESERVE_LOCK).single()
        refreshed.registeredAt shouldBeEqualTo original.registeredAt
        refreshed.lastStartTime shouldBeEqualTo original.lastStartTime
        refreshed.lastCompletionTime shouldBeEqualTo original.lastCompletionTime
        refreshed.successCount shouldBeEqualTo original.successCount
        refreshed.failureCount shouldBeEqualTo original.failureCount
        refreshed.metadata shouldBeEqualTo NEW_METADATA
        fixture.ttlFor(PRESERVE_LOCK, fixture.nodeId) shouldBeEqualTo REFRESH_TTL
    }

    private suspend fun assertSuspendRefreshContract(
        fixture: SuspendFixture,
        register: suspend (String, CandidateInfo, Duration) -> Unit,
        refresh: suspend (String, CandidateInfo, Duration) -> Unit,
        list: suspend (String) -> List<CandidateInfo>,
    ) {
        refresh(
            MISSING_LOCK,
            CandidateInfo("missing", metadata = mapOf("heartbeat" to "missing")),
            REFRESH_TTL,
        )
        list(MISSING_LOCK).shouldBeEmpty()
        fixture.registrationCount shouldBeEqualTo 0

        register(EXPIRED_LOCK, CandidateInfo("expired"), INITIAL_TTL)
        fixture.expireCandidate(EXPIRED_LOCK, "expired")
        list(EXPIRED_LOCK).shouldBeEmpty()
        val registrationsBeforeRefresh = fixture.registrationCount

        refresh(
            EXPIRED_LOCK,
            CandidateInfo("expired", metadata = mapOf("heartbeat" to "expired")),
            REFRESH_TTL,
        )

        list(EXPIRED_LOCK).shouldBeEmpty()
        fixture.registrationCount shouldBeEqualTo registrationsBeforeRefresh

        val original = candidateInfo(fixture.nodeId)
        register(PRESERVE_LOCK, original, INITIAL_TTL)

        refresh(
            PRESERVE_LOCK,
            CandidateInfo(fixture.nodeId, metadata = NEW_METADATA),
            REFRESH_TTL,
        )

        val refreshed = list(PRESERVE_LOCK).single()
        refreshed.registeredAt shouldBeEqualTo original.registeredAt
        refreshed.lastStartTime shouldBeEqualTo original.lastStartTime
        refreshed.lastCompletionTime shouldBeEqualTo original.lastCompletionTime
        refreshed.successCount shouldBeEqualTo original.successCount
        refreshed.failureCount shouldBeEqualTo original.failureCount
        refreshed.metadata shouldBeEqualTo NEW_METADATA
        fixture.ttlFor(PRESERVE_LOCK, fixture.nodeId) shouldBeEqualTo REFRESH_TTL
    }

    private fun candidateInfo(nodeId: String) = CandidateInfo(
        nodeId = nodeId,
        registeredAt = Instant.parse("2026-01-01T00:00:00Z"),
        lastStartTime = Instant.parse("2026-01-01T00:01:00Z"),
        lastCompletionTime = Instant.parse("2026-01-01T00:02:00Z"),
        successCount = 7,
        failureCount = 2,
        metadata = mapOf("version" to "old"),
    )

    private interface FixtureState {
        val nodeId: String
        val registrationCount: Int

        fun expireCandidate(lockName: String, nodeId: String)

        fun ttlFor(lockName: String, nodeId: String): Duration?
    }

    private interface BlockingFixture : FixtureState

    private interface SuspendFixture : FixtureState

    private abstract class BlockingSingleFixtureBase : StrategicLeaderElector, BlockingFixture {
        private val store = CandidateStore()

        override val nodeId: String = "fixture-node"
        override val registrationCount: Int get() = store.registrationCount

        override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
            store.register(lockName, info, ttl)
        }

        override fun unregisterCandidate(lockName: String, nodeId: String) {
            store.unregister(lockName, nodeId)
        }

        override fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

        override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
            store.updateResult(lockName, nodeId, result)
        }

        override fun <T> runIfLeader(
            lockName: String,
            strategy: ElectionStrategy,
            options: LeaderElectionOptions,
            action: () -> T,
        ): T? = error("runIfLeader is not used by this fixture")

        override fun expireCandidate(lockName: String, nodeId: String) {
            store.expire(lockName, nodeId)
        }

        override fun ttlFor(lockName: String, nodeId: String): Duration? = store.ttl(lockName, nodeId)
    }

    private class BlockingSingleFixture : BlockingSingleFixtureBase()

    private abstract class BlockingGroupFixtureBase : StrategicLeaderGroupElector, BlockingFixture {
        private val store = CandidateStore()

        override val nodeId: String = "fixture-node"
        override val registrationCount: Int get() = store.registrationCount

        override fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
            store.register(lockName, info, ttl)
        }

        override fun unregisterCandidate(lockName: String, nodeId: String) {
            store.unregister(lockName, nodeId)
        }

        override fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

        override fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
            store.updateResult(lockName, nodeId, result)
        }

        override fun <T> runIfLeader(
            lockName: String,
            strategy: GroupElectionStrategy,
            maxLeaders: Int,
            action: () -> T,
        ): T? = error("runIfLeader is not used by this fixture")

        override fun expireCandidate(lockName: String, nodeId: String) {
            store.expire(lockName, nodeId)
        }

        override fun ttlFor(lockName: String, nodeId: String): Duration? = store.ttl(lockName, nodeId)
    }

    private class BlockingGroupFixture : BlockingGroupFixtureBase()

    private abstract class SuspendSingleFixtureBase : StrategicSuspendLeaderElector, SuspendFixture {
        private val store = CandidateStore()

        override val nodeId: String = "fixture-node"
        override val registrationCount: Int get() = store.registrationCount

        override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
            store.register(lockName, info, ttl)
        }

        override suspend fun unregisterCandidate(lockName: String, nodeId: String) {
            store.unregister(lockName, nodeId)
        }

        override suspend fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

        override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
            store.updateResult(lockName, nodeId, result)
        }

        override suspend fun <T> runIfLeader(
            lockName: String,
            strategy: ElectionStrategy,
            options: LeaderElectionOptions,
            action: suspend () -> T,
        ): T? = error("runIfLeader is not used by this fixture")

        override fun expireCandidate(lockName: String, nodeId: String) {
            store.expire(lockName, nodeId)
        }

        override fun ttlFor(lockName: String, nodeId: String): Duration? = store.ttl(lockName, nodeId)
    }

    private class SuspendSingleFixture : SuspendSingleFixtureBase()

    private abstract class SuspendGroupFixtureBase : StrategicSuspendLeaderGroupElector, SuspendFixture {
        private val store = CandidateStore()

        override val nodeId: String = "fixture-node"
        override val registrationCount: Int get() = store.registrationCount

        override suspend fun registerCandidate(lockName: String, info: CandidateInfo, ttl: Duration) {
            store.register(lockName, info, ttl)
        }

        override suspend fun unregisterCandidate(lockName: String, nodeId: String) {
            store.unregister(lockName, nodeId)
        }

        override suspend fun listCandidates(lockName: String): List<CandidateInfo> = store.list(lockName)

        override suspend fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
            store.updateResult(lockName, nodeId, result)
        }

        override suspend fun <T> runIfLeader(
            lockName: String,
            strategy: GroupElectionStrategy,
            maxLeaders: Int,
            action: suspend () -> T,
        ): T? = error("runIfLeader is not used by this fixture")

        override fun expireCandidate(lockName: String, nodeId: String) {
            store.expire(lockName, nodeId)
        }

        override fun ttlFor(lockName: String, nodeId: String): Duration? = store.ttl(lockName, nodeId)
    }

    private class SuspendGroupFixture : SuspendGroupFixtureBase()

    private class CandidateStore {
        private val entries = mutableMapOf<CandidateKey, CandidateEntry>()
        var registrationCount: Int = 0
            private set

        fun register(lockName: String, info: CandidateInfo, ttl: Duration) {
            entries[CandidateKey(lockName, info.nodeId)] = CandidateEntry(info, ttl)
            registrationCount += 1
        }

        fun unregister(lockName: String, nodeId: String) {
            entries.remove(CandidateKey(lockName, nodeId))
        }

        fun list(lockName: String): List<CandidateInfo> = entries
            .filterKeys { it.lockName == lockName }
            .values
            .filterNot { it.expired }
            .map { it.info }

        fun updateResult(lockName: String, nodeId: String, result: CandidateResult) {
            entries[CandidateKey(lockName, nodeId)]?.let { entry ->
                entry.info = entry.info.withResult(result)
            }
        }

        fun expire(lockName: String, nodeId: String) {
            entries[CandidateKey(lockName, nodeId)]?.expired = true
        }

        fun ttl(lockName: String, nodeId: String): Duration? =
            entries[CandidateKey(lockName, nodeId)]?.ttl
    }

    private data class CandidateKey(val lockName: String, val nodeId: String)

    private data class CandidateEntry(
        var info: CandidateInfo,
        val ttl: Duration,
        var expired: Boolean = false,
    )

    private companion object {
        const val MISSING_LOCK = "strategic-refresh-missing"
        const val EXPIRED_LOCK = "strategic-refresh-expired"
        const val PRESERVE_LOCK = "strategic-refresh-preserve"
        val INITIAL_TTL = 30.seconds
        val REFRESH_TTL = 60.seconds
        val NEW_METADATA = mapOf("version" to "new")
    }
}

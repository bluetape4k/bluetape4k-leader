@file:OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)

package io.bluetape4k.leader.lettuce

import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.codec.Base58
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.leader.strategy.CandidateInfo
import io.bluetape4k.leader.strategy.CandidateResult
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.reactive.RedisReactiveCommands
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.reactive.RedisAdvancedClusterReactiveCommands
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands
import io.lettuce.core.cluster.api.sync.RedisClusterCommands
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LettuceMigrationRaceTest : AbstractLettuceLeaderTest() {

    @TestFactory
    fun `blocking과 suspend의 실제 source 및 v3 writer 경합`() =
        listOf(false, true).flatMap { suspending ->
            MigrationSource.entries.flatMap { source ->
                MigrationRace.entries.map { race ->
                    DynamicTest.dynamicTest("$suspending $source $race 실제 Redis") {
                        runSuspendIO {
                            val scenario = MigrationRaceScenario(connection.sync(), source)
                            val intercepted = scenario.wrap(connection)
                            scenario.verifyRace(race) {
                                if (suspending) LettuceSuspendCandidateRegistry(intercepted).listCandidates(scenario.lockName)
                                else LettuceCandidateRegistry(intercepted).listCandidates(scenario.lockName)
                            }
                            connection.sync().ping() shouldBeEqualTo "PONG"
                        }
                    }
                }
            }
        }

    @TestFactory
    fun `주입한 PTTL 경계는 실제 Redis TTL 관측과 구분한다`() =
        listOf(false, true).flatMap { suspending ->
            listOf(false, true).flatMap { postCopy ->
                listOf(-1L, 0L, -2L, 1L).map { ttl ->
                    DynamicTest.dynamicTest("$suspending postCopy=$postCopy injectedPTTL=$ttl") {
                        runSuspendIO {
                            val scenario = MigrationRaceScenario(connection.sync(), MigrationSource.V2)
                            val intercepted = scenario.wrap(connection)
                            scenario.verifyInjectedTtl(ttl, postCopy) {
                                if (suspending) LettuceSuspendCandidateRegistry(intercepted).listCandidates(scenario.lockName)
                                else LettuceCandidateRegistry(intercepted).listCandidates(scenario.lockName)
                            }
                            connection.sync().ping() shouldBeEqualTo "PONG"
                        }
                    }
                }
            }
        }
}

internal enum class MigrationSource { V2, COLON }

internal enum class MigrationRace {
    CHANGE_BEFORE_TTL, DELETE_BEFORE_TTL, EXPIRE_BEFORE_TTL, SAME_BEFORE_TTL,
    CHANGE_AFTER_COPY, DELETE_AFTER_COPY, EXPIRE_AFTER_COPY, SAME_AFTER_COPY,
    REGISTER_SAME_AFTER_COPY, REGISTER_CHANGED_AFTER_COPY,
    REFRESH_SAME_AFTER_COPY, RESULT_AFTER_COPY,
}

/**
 * 실제 Redis 명령 사이에 writer 완료를 배치하는 테스트 전용 시나리오다.
 * stress helper의 확률적 스케줄 대신 GET/PTTL 경계를 고정하며 private 구현에는 접근하지 않는다.
 * reactive 경로의 동기 writer는 boundedElastic에서 실행해 Lettuce event loop를 차단하지 않는다.
 */
internal class MigrationRaceScenario(
    private val actual: RedisClusterCommands<String, String>,
    source: MigrationSource,
) {
    val lockName = "migration-race-${Base58.randomString(12)}"
    private val nodeId = "node"
    private val prefix = LettuceCandidateRegistry.DEFAULT_KEY_PREFIX
    private val original = CandidateInfo(
        nodeId, registeredAt = java.time.Instant.ofEpochMilli(1_000L), metadata = mapOf("writer" to "original"),
    )
    private val changed = original.copy(metadata = mapOf("writer" to "new"))
    private val raw = LettuceCandidateInfoCodec.encode(original)
    private val changedRaw = LettuceCandidateInfoCodec.encode(changed)
    private val sourceKey = when (source) {
        MigrationSource.V2 -> LettuceCandidateKeyCodec.v2CandidateKey(prefix, lockName, nodeId)
        MigrationSource.COLON -> LettuceCandidateKeyCodec.legacyCandidateKey(prefix, lockName, nodeId)
    }
    private val sourceIndex = when (source) {
        MigrationSource.V2 -> LettuceCandidateKeyCodec.v2IndexKey(prefix, lockName)
        MigrationSource.COLON -> LettuceCandidateKeyCodec.legacyIndexKey(prefix, lockName)
    }
    private val destination = LettuceCandidateKeyCodec.candidateKey(prefix, lockName, nodeId)
    private val destinationIndex = LettuceCandidateKeyCodec.indexKey(prefix, lockName)
    private val token = LettuceCandidateKeyCodec.migrationTokenKey(prefix, lockName, nodeId)
    private val tombstone = LettuceCandidateKeyCodec.tombstoneKey(prefix, lockName, nodeId)
    private var beforeFirstTtl: () -> Unit = {}
    private var afterCopy: () -> Unit = {}
    private var ttlReads = 0
    private var copyObserved = false
    private val actualTtls = mutableListOf<Long>()
    private var injectedSnapshot: Long? = null
    private var injectedRecheck: Long? = null

    suspend fun verifyRace(race: MigrationRace, list: suspend () -> List<CandidateInfo>) {
        var writerCalls = 0
        val beforeTtl = race in setOf(
            MigrationRace.CHANGE_BEFORE_TTL, MigrationRace.DELETE_BEFORE_TTL,
            MigrationRace.EXPIRE_BEFORE_TTL, MigrationRace.SAME_BEFORE_TTL,
        )
        val writer = {
            writerCalls++
            when (race) {
                MigrationRace.CHANGE_BEFORE_TTL, MigrationRace.CHANGE_AFTER_COPY -> actual.set(sourceKey, changedRaw)
                MigrationRace.DELETE_BEFORE_TTL, MigrationRace.DELETE_AFTER_COPY -> actual.del(sourceKey)
                MigrationRace.EXPIRE_BEFORE_TTL, MigrationRace.EXPIRE_AFTER_COPY -> {
                    actual.pexpire(sourceKey, 0L) shouldBeEqualTo true
                    actual.pttl(sourceKey) shouldBeEqualTo -2L
                }
                MigrationRace.SAME_BEFORE_TTL, MigrationRace.SAME_AFTER_COPY -> actual.set(sourceKey, raw)
                else -> {
                    // 복사 후 일반 writer와 source 삭제를 완료한 다음 이전 migration의 cleanup을 재개한다.
                    writeCurrent(race)
                    actual.get(token).shouldBeNull()
                    actual.del(sourceKey)
                }
            }
            Unit
        }
        if (beforeTtl) beforeFirstTtl = writer else afterCopy = writer
        try {
            seed()
            actual.pexpire(sourceKey, 30_000L) shouldBeEqualTo true
            actual.pttl(sourceKey) shouldBeGreaterThan 0L
            val listed = list()
            writerCalls shouldBeEqualTo 1
            val deleted = race in setOf(
                MigrationRace.DELETE_BEFORE_TTL, MigrationRace.DELETE_AFTER_COPY,
                MigrationRace.EXPIRE_BEFORE_TTL, MigrationRace.EXPIRE_AFTER_COPY,
            )
            if (deleted) {
                listed.shouldBeEmpty()
                actual.get(destination).shouldBeNull()
                actual.get(token).shouldBeNull()
                actual.sismember(destinationIndex, nodeId) shouldBeEqualTo false
                actual.get(sourceKey).shouldBeNull()
            } else {
                val candidate = listed.single()
                candidate.nodeId shouldBeEqualTo nodeId
                when (race) {
                    MigrationRace.REGISTER_CHANGED_AFTER_COPY -> candidate shouldBeEqualTo changed
                    MigrationRace.RESULT_AFTER_COPY -> candidate.successCount shouldBeEqualTo 1L
                    else -> candidate shouldBeEqualTo original
                }
                actual.get(destination) shouldBeEqualTo LettuceCandidateInfoCodec.encode(candidate)
                actual.sismember(destinationIndex, nodeId) shouldBeEqualTo true
                if (!isCurrentWriter(race)) {
                    val changedSource = race == MigrationRace.CHANGE_BEFORE_TTL || race == MigrationRace.CHANGE_AFTER_COPY
                    actual.get(sourceKey) shouldBeEqualTo if (changedSource) changedRaw else raw
                    actual.pttl(sourceKey) shouldBeEqualTo -1L
                    actual.sismember(sourceIndex, nodeId) shouldBeEqualTo true
                    actual.get(token).shouldNotBeNull()
                } else {
                    actual.get(sourceKey).shouldBeNull()
                    actual.get(token).shouldBeNull()
                }
            }
            copyObserved shouldBeEqualTo !(deleted && beforeTtl)
            if (!beforeTtl) ttlReads shouldBeEqualTo 2
        } finally {
            cleanup()
        }
    }

    suspend fun verifyInjectedTtl(ttl: Long, postCopy: Boolean, list: suspend () -> List<CandidateInfo>) {
        if (postCopy) {
            injectedSnapshot = 30_000L
            injectedRecheck = ttl
        } else {
            injectedSnapshot = ttl
        }
        try {
            seed()
            // 실측은 -1이다. 정확한 0/1ms 분기를 네트워크 시간에 의존시키지 않고 반환값만 주입한다.
            actual.pttl(sourceKey) shouldBeEqualTo -1L
            val listed = list()
            actualTtls.toSet() shouldBeEqualTo setOf(-1L)
            actual.get(sourceKey) shouldBeEqualTo raw
            actual.pttl(sourceKey) shouldBeEqualTo -1L
            val rejected = ttl == 0L || ttl == -2L
            if (rejected) {
                listed.shouldBeEmpty()
                actual.get(destination).shouldBeNull()
                actual.get(token).shouldBeNull()
                actual.sismember(destinationIndex, nodeId) shouldBeEqualTo false
            } else if (!postCopy && ttl == 1L) {
                // 실제 Lua의 PX 1 값은 재조회 전에 만료될 수 있다. token은 복사 성공 때만 생성된다.
                actual.get(token).shouldNotBeNull()
                listed.forEach { it shouldBeEqualTo original }
            } else {
                listed.single() shouldBeEqualTo original
                actual.get(destination) shouldBeEqualTo raw
                actual.get(token).shouldNotBeNull()
                if (postCopy) actual.pttl(destination) shouldBeGreaterThan 0L
                else actual.pttl(destination) shouldBeEqualTo -1L
            }
            ttlReads shouldBeEqualTo if (postCopy || !rejected) 2 else 1
        } finally {
            cleanup()
        }
    }

    private fun seed() {
        actual.set(sourceKey, raw)
        actual.sadd(sourceIndex, nodeId)
    }

    private fun isCurrentWriter(race: MigrationRace): Boolean = race in setOf(
        MigrationRace.REGISTER_SAME_AFTER_COPY, MigrationRace.REGISTER_CHANGED_AFTER_COPY,
        MigrationRace.REFRESH_SAME_AFTER_COPY, MigrationRace.RESULT_AFTER_COPY,
    )

    private fun beforeGet(key: String) {
        if (key == sourceKey && ttlReads == 1 && !copyObserved) {
            copyObserved = true
            actual.get(token).shouldNotBeNull()
            if (injectedSnapshot != 1L) actual.get(destination) shouldBeEqualTo raw
            afterCopy()
        }
    }

    private fun beforeTtl(key: String) {
        if (key == sourceKey && ttlReads == 0) beforeFirstTtl()
    }

    private fun observedTtl(key: String, value: Long): Long {
        if (key != sourceKey) return value
        actualTtls += value
        ttlReads++
        return (if (ttlReads == 1) injectedSnapshot else injectedRecheck) ?: value
    }

    private fun writeCurrent(race: MigrationRace) {
        val keys = arrayOf(destination, destinationIndex, tombstone, token)
        when (race) {
            MigrationRace.REGISTER_SAME_AFTER_COPY, MigrationRace.REGISTER_CHANGED_AFTER_COPY ->
                io.bluetape4k.leader.lettuce.script.RedisScriptRunner.run<List<Any>>(
                    actual, LettuceCandidateWriteScript.WRITE, io.lettuce.core.ScriptOutputType.MULTI,
                    keys, LettuceCandidateWriteScript.REGISTER,
                    if (race == MigrationRace.REGISTER_CHANGED_AFTER_COPY) changedRaw else raw, "0", nodeId,
                )
            MigrationRace.REFRESH_SAME_AFTER_COPY ->
                io.bluetape4k.leader.lettuce.script.RedisScriptRunner.run<List<Any>>(
                    actual, LettuceCandidateRefreshScript.REFRESH, io.lettuce.core.ScriptOutputType.MULTI,
                    arrayOf(destination, destinationIndex, token), raw, "0",
                )
            MigrationRace.RESULT_AFTER_COPY ->
                io.bluetape4k.leader.lettuce.script.RedisScriptRunner.run<List<Any>>(
                    actual, LettuceCandidateResultScript.UPDATE, io.lettuce.core.ScriptOutputType.MULTI,
                    arrayOf(destination, token), CandidateResult.SUCCESS.name, "123",
                )
            else -> error("v3 writer 시나리오가 아님: $race")
        }
    }

    private fun cleanup() {
        // legacy/v3는 서로 다른 slot일 수 있으므로 다중 key DEL을 쓰지 않는다.
        listOf(sourceKey, sourceIndex, destination, destinationIndex, token, tombstone).forEach(actual::del)
    }

    fun wrap(connection: StatefulRedisConnection<String, String>): StatefulRedisConnection<String, String> {
        val sync = connection.sync()
        val reactive = connection.reactive()
        val syncCommands = object : RedisCommands<String, String> by sync {
            override fun get(key: String): String? { beforeGet(key); return sync.get(key) }
            override fun pttl(key: String): Long { beforeTtl(key); return observedTtl(key, sync.pttl(key)) }
        }
        val reactiveCommands = object : RedisReactiveCommands<String, String> by reactive {
            override fun get(key: String): Mono<String> = beforeRead { beforeGet(key) }.then(reactive.get(key))
            override fun pttl(key: String): Mono<Long> = beforeRead { beforeTtl(key) }
                .then(reactive.pttl(key)).map { observedTtl(key, it) }
        }
        return object : StatefulRedisConnection<String, String> by connection {
            override fun sync(): RedisCommands<String, String> = syncCommands
            override fun reactive(): RedisReactiveCommands<String, String> = reactiveCommands
        }
    }

    fun wrap(connection: StatefulRedisClusterConnection<String, String>): StatefulRedisClusterConnection<String, String> {
        val sync = connection.sync()
        val reactive = connection.reactive()
        val syncCommands = object : RedisAdvancedClusterCommands<String, String> by sync {
            override fun get(key: String): String? { beforeGet(key); return sync.get(key) }
            override fun pttl(key: String): Long { beforeTtl(key); return observedTtl(key, sync.pttl(key)) }
        }
        val reactiveCommands = object : RedisAdvancedClusterReactiveCommands<String, String> by reactive {
            override fun get(key: String): Mono<String> = beforeRead { beforeGet(key) }.then(reactive.get(key))
            override fun pttl(key: String): Mono<Long> = beforeRead { beforeTtl(key) }
                .then(reactive.pttl(key)).map { observedTtl(key, it) }
        }
        return object : StatefulRedisClusterConnection<String, String> by connection {
            override fun sync(): RedisAdvancedClusterCommands<String, String> = syncCommands
            override fun reactive(): RedisAdvancedClusterReactiveCommands<String, String> = reactiveCommands
        }
    }

    private fun beforeRead(action: () -> Unit): Mono<Unit> =
        Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic())
}

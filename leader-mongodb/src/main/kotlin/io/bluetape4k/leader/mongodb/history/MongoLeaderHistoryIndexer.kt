package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requirePositiveNumber
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val SHUTDOWN_TIMEOUT_METRIC_VALUE = -2

/** MongoDB history 인덱스 빌드의 수명 주기 상태입니다. */
enum class MongoLeaderHistoryIndexState(val metricValue: Int) {
    BUILDING(0),
    READY(1),
    FAILED(-1),
    SHUTDOWN_TIMEOUT(SHUTDOWN_TIMEOUT_METRIC_VALUE),
}

/**
 * `MongoLeaderHistoryIndexer`는 MongoDB backend의 leader election, lock lease, ownership 확인을 담당합니다.
 *
 * 정상 lock contention은 예외가 아니라 skip/null/result 상태로 표현한다는 core 계약을 보존합니다.
 * @property database MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 * @property config MongoDB backend 호출과 상태 계산에 사용하는 속성입니다.
 */
class MongoLeaderHistoryIndexer(
    private val database: MongoDatabase,
    private val config: MongoHistoryConfig = MongoHistoryConfig(),
    registry: MeterRegistry? = null,
) : AutoCloseable {

    companion object : KLoggingChannel() {
        const val SHUTDOWN_TIMEOUT_MS = 5_000L

        private const val GAUGE_INDEX_STATE = "leader.history.mongodb.index.state"
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 1_000L

        private const val INDEX_LOCK_NAME_STARTED = "lockName_1_startedAt_-1"
        private const val INDEX_TOKEN = "token_1"
        private const val INDEX_TTL_STARTED = "startedAt_1"
    }

    private val state = AtomicReference(MongoLeaderHistoryIndexState.BUILDING)

    /** 현재 MongoDB history 인덱스 빌드의 수명 주기 상태입니다. */
    val indexLifecycleState: MongoLeaderHistoryIndexState get() = state.get()

    /** 기존 메트릭 상태 코드와 호환되는 수명 주기 상태 값입니다. */
    val indexState: Int get() = indexLifecycleState.metricValue

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var indexBuildJob: Job? = null

    init {
        registry?.let { reg ->
            Gauge.builder(GAUGE_INDEX_STATE) { indexState.toDouble() }
                .description(
                    "MongoDB leader history index build state: " +
                        "-2=shutdown-timeout, -1=failed, 0=building, 1=ready",
                )
                .register(reg)
        }
        indexBuildJob = scope.launch { buildIndexesWithRetry() }
    }

    private suspend fun buildIndexesWithRetry() {
        val collection = database.getCollection<org.bson.Document>(config.collectionName)
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                log.debug { "Building MongoDB history indexes (attempt ${attempt + 1}/$MAX_RETRIES)" }

                collection.createIndex(
                    Indexes.compoundIndex(Indexes.ascending("lockName"), Indexes.descending("startedAt")),
                    IndexOptions().name(INDEX_LOCK_NAME_STARTED).background(true),
                )

                collection.createIndex(
                    Indexes.ascending("token"),
                    IndexOptions().name(INDEX_TOKEN).background(true),
                )

                if (config.ttlDays > 0) {
                    collection.createIndex(
                        Indexes.ascending("startedAt"),
                        IndexOptions()
                            .name(INDEX_TTL_STARTED)
                            .expireAfter(config.ttlDays * 86400L, TimeUnit.SECONDS)
                            .background(true),
                    )
                    log.info { "MongoDB history TTL index created: ttlDays=${config.ttlDays}" }
                }

                if (state.compareAndSet(MongoLeaderHistoryIndexState.BUILDING, MongoLeaderHistoryIndexState.READY)) {
                    log.info { "MongoDB history indexes ready" }
                } else {
                    log.debug {
                        "MongoDB history index build completed after terminal lifecycle state; " +
                            "state=${indexLifecycleState.name}"
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val delayMs = BASE_DELAY_MS * (1L shl (attempt - 1))
                if (attempt >= MAX_RETRIES) {
                    val failed = state.compareAndSet(
                        MongoLeaderHistoryIndexState.BUILDING,
                        MongoLeaderHistoryIndexState.FAILED,
                    )
                    log.error(e) {
                        if (failed) {
                            "MongoDB history index build failed after $MAX_RETRIES attempts"
                        } else {
                            "MongoDB history index build failed after terminal lifecycle state; " +
                                "state=${indexLifecycleState.name}"
                        }
                    }
                } else {
                    log.warn(e) { "MongoDB history index build attempt $attempt failed, retrying in ${delayMs}ms" }
                    delay(delayMs)
                }
            }
        }
    }

    /**
     * `close` 호출은 MongoDB backend leader election 계약의 일부 동작을 수행합니다.
     *
     * API 이름과 `lock`, `lease`, `watchdog`, `slot`, `schema`, `history` 용어는 기존 계약과 동일하게 유지합니다.
     */
    override fun close() = runBlocking { closeSuspend() }

    /**
     * 코루틴 호출자는 blocking bridge 없이 인덱스 build job의 종료를 기다립니다.
     *
     * caller cancellation은 그대로 전파하고, shutdown timeout만 경고 후 반환합니다.
     *
     * @param shutdownTimeoutMs 인덱스 build 종료를 기다릴 양수 밀리초입니다.
     */
    internal suspend fun closeSuspend(shutdownTimeoutMs: Long = SHUTDOWN_TIMEOUT_MS) {
        val validShutdownTimeoutMs = shutdownTimeoutMs.requirePositiveNumber("shutdownTimeoutMs")
        scope.cancel()
        val stopped = withTimeoutOrNull(validShutdownTimeoutMs) {
            indexBuildJob?.join()
            true
        } ?: false

        if (!stopped) {
            state.set(MongoLeaderHistoryIndexState.SHUTDOWN_TIMEOUT)
            log.warn {
                "MongoLeaderHistoryIndexer: shutdown timed out after ${validShutdownTimeoutMs}ms; " +
                    "state=${indexLifecycleState.name}"
            }
        }
    }
}

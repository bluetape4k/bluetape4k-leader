package io.bluetape4k.leader.mongodb.history

import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoDatabase
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.error
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds and maintains MongoDB indexes for the leader-lock history collection.
 *
 * Index creation runs asynchronously in a background coroutine at startup so that
 * the application does not block waiting for `createIndex` to complete.  Use
 * [indexState] or the `leader.history.mongodb.index.state` gauge to observe
 * readiness.
 *
 * ## Index set
 * - `{ lockName: 1, startedAt: -1 }` — compound query index
 * - `{ token: 1 }` — token lookup index
 * - `{ startedAt: 1 }` with `expireAfterSeconds` — TTL index (only when
 *   [MongoHistoryConfig.ttlDays] > 0)
 *
 * ## Index state gauge
 * | Value | Meaning |
 * |-------|---------|
 * | `0.0` | Build in progress |
 * | `1.0` | All indexes ready |
 * | `-1.0` | Build failed after retries |
 *
 * ## Lifecycle
 * Call [close] on application shutdown.  It cancels the background scope and
 * waits up to [SHUTDOWN_TIMEOUT_MS] milliseconds for the index job to finish.
 *
 * @param database MongoDB coroutine [MongoDatabase] instance.
 * @param config Collection name and TTL settings.
 * @param registry Optional [MeterRegistry] for the index-state gauge.
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

    /** `-1` failed, `0` in-progress, `1` ready. */
    private val _indexState = AtomicInteger(0)
    val indexState: Int get() = _indexState.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var indexBuildJob: Job? = null

    init {
        registry?.let { reg ->
            Gauge.builder(GAUGE_INDEX_STATE) { _indexState.get().toDouble() }
                .description("MongoDB leader history index build state: -1=failed, 0=building, 1=ready")
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

                _indexState.set(1)
                log.info { "MongoDB history indexes ready" }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                val delayMs = BASE_DELAY_MS * (1L shl (attempt - 1))
                if (attempt >= MAX_RETRIES) {
                    _indexState.set(-1)
                    log.error(e) { "MongoDB history index build failed after $MAX_RETRIES attempts" }
                } else {
                    log.warn(e) { "MongoDB history index build attempt $attempt failed, retrying in ${delayMs}ms" }
                    delay(delayMs)
                }
            }
        }
    }

    /**
     * Cancels the background scope and waits up to [SHUTDOWN_TIMEOUT_MS] for
     * the index job to complete.
     */
    override fun close() {
        runCatching { scope.cancel() }
        runCatching {
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                    indexBuildJob?.join()
                } ?: log.warn { "MongoLeaderHistoryIndexer: shutdown timed out after ${SHUTDOWN_TIMEOUT_MS}ms" }
            }
        }
    }
}

package io.bluetape4k.leader.ktor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * `LeaderElectionShutdownReport`는 Ktor plugin 소유 resource 정리 결과를 나타냅니다.
 */
internal data class LeaderElectionShutdownReport(
    val attempted: Int,
    val closed: Int,
    val failures: Int,
    val timedOutJobs: Int,
    val failureKinds: Map<String, Int> = emptyMap(),
    val timeoutKinds: Map<String, Int> = emptyMap(),
)

/**
 * Application-owned resource의 lifecycle을 Ktor application과 연결하는 internal 계약입니다.
 */
internal interface LeaderElectionResourceRegistry : AutoCloseable {
    fun register(resource: AutoCloseable): AutoCloseable
    fun register(job: Job): AutoCloseable
    val lastShutdownReport: LeaderElectionShutdownReport?
    suspend fun awaitClosed(): LeaderElectionShutdownReport
}

/**
 * Registry의 등록, 종료, registration token을 하나의 linearization 경계로 직렬화합니다.
 *
 * 실제 resource의 `close`, Job cancellation, bounded join은 registry lock 밖의
 * registry-owned cleanup scope에서 수행하여 resource 재진입과 shutdown deadlock을 막습니다.
 */
internal class LeaderElectionResourceRegistryImpl(
    private val jobJoinTimeout: Duration = 2.seconds,
) : LeaderElectionResourceRegistry {

    private val lock = ReentrantLock()
    private val entries = mutableListOf<Entry>()
    private val cleanupSupervisor = SupervisorJob()
    private val cleanupScope = CoroutineScope(
        cleanupSupervisor + Dispatchers.IO.limitedParallelism(1),
    )
    private val closedCompletion = kotlinx.coroutines.CompletableDeferred<LeaderElectionShutdownReport>()
    private val shutdownObservers = mutableListOf<(LeaderElectionShutdownReport) -> Unit>()

    @Volatile
    private var closed = false

    @Volatile
    private var shutdownReport: LeaderElectionShutdownReport? = null

    init {
        require(jobJoinTimeout.isFinite() && jobJoinTimeout.isPositive()) {
            "jobJoinTimeout은 양수이면서 유한해야 합니다: $jobJoinTimeout"
        }
    }

    override val lastShutdownReport: LeaderElectionShutdownReport?
        get() = shutdownReport

    override fun register(resource: AutoCloseable): AutoCloseable {
        val entry = Entry(resource = resource, job = null)
        val token = lock.withLock {
            if (closed) {
                null
            } else {
                entries += entry
                RegistrationToken(entry)
            }
        }

        if (token != null) return token

        closeResourceImmediately(entry)
        return NoOpRegistrationToken
    }

    override fun register(job: Job): AutoCloseable {
        val entry = Entry(resource = null, job = job)
        val token = lock.withLock {
            if (closed) {
                null
            } else {
                entries += entry
                RegistrationToken(entry)
            }
        }

        if (token != null) return token

        job.cancel()
        return NoOpRegistrationToken
    }

    override fun close() {
        val drained = lock.withLock {
            if (closed) return

            closed = true
            entries.asReversed().toList().also { snapshot ->
                snapshot.forEach { it.claimed = true }
                entries.clear()
            }
        }

        // Cancellation is intentionally immediate, but never while holding the registry lock.
        drained.forEach { it.job?.cancel() }

        cleanupScope.launch {
            val report = cleanup(drained)
            val observers = lock.withLock {
                shutdownReport = report
                shutdownObservers.toList().also { shutdownObservers.clear() }
            }
            observers.forEach { observer ->
                runCatching { observer(report) }
            }
            closedCompletion.complete(report)
            // The registry owns this scope; no dispatcher or supervisor survives completion.
            cleanupSupervisor.cancel()
        }
    }

    override suspend fun awaitClosed(): LeaderElectionShutdownReport =
        closedCompletion.await()

    internal fun observeShutdown(observer: (LeaderElectionShutdownReport) -> Unit) {
        val completed = lock.withLock {
            shutdownReport ?: run {
                shutdownObservers += observer
                null
            }
        }
        completed?.let(observer)
    }

    private suspend fun cleanup(drained: List<Entry>): LeaderElectionShutdownReport {
        var closedCount = 0
        var failures = 0
        var timedOutJobs = 0
        val failureKinds = mutableMapOf<String, Int>()
        val timeoutKinds = mutableMapOf<String, Int>()

        for (entry in drained) {
            if (entry.job != null) {
                val joined = try {
                    withContext(NonCancellable) {
                        withTimeoutOrNull(jobJoinTimeout) {
                            entry.job.join()
                            true
                        } ?: false
                    }
                } catch (_: CancellationException) {
                    // Job cancellation is the expected shutdown signal, not a cleanup failure.
                    true
                } catch (_: Throwable) {
                    failures++
                    failureKinds.increment("job")
                    false
                }

                if (!joined) {
                    timedOutJobs++
                    timeoutKinds.increment("job")
                } else {
                    closedCount++
                }
                continue
            }

            try {
                withContext(NonCancellable) { entry.resource!!.close() }
                closedCount++
            } catch (_: Throwable) {
                failures++
                failureKinds.increment("resource")
            }
        }

        return LeaderElectionShutdownReport(
            attempted = drained.size,
            closed = closedCount,
            failures = failures,
            timedOutJobs = timedOutJobs,
            failureKinds = failureKinds.toMap(),
            timeoutKinds = timeoutKinds.toMap(),
        )
    }

    private fun closeResourceImmediately(entry: Entry) {
        if (entry.job != null) {
            entry.job.cancel()
        } else {
            entry.resource!!.close()
        }
    }

    private inner class RegistrationToken(
        private val entry: Entry,
    ) : AutoCloseable {
        override fun close() {
            val claimed = lock.withLock {
                if (entry.claimed) {
                    false
                } else {
                    entry.claimed = true
                    entries.remove(entry)
                    true
                }
            }
            if (claimed) closeResourceImmediately(entry)
        }
    }

    private data class Entry(
        val resource: AutoCloseable?,
        val job: Job?,
        var claimed: Boolean = false,
    )

    private object NoOpRegistrationToken : AutoCloseable {
        override fun close() = Unit
    }

    private fun MutableMap<String, Int>.increment(kind: String) {
        this[kind] = (this[kind] ?: 0) + 1
    }
}

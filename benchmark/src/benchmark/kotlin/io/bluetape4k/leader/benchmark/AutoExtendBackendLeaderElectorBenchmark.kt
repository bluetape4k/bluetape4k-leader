package io.bluetape4k.leader.benchmark

import io.bluetape4k.codec.Base58
import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.LeaderElector
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.coroutines.SuspendLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.mongodb.MongoLeaderElectionOptions
import io.bluetape4k.leader.mongodb.MongoLeaderElector
import io.bluetape4k.leader.mongodb.MongoSuspendLeaderElector
import io.bluetape4k.leader.mongodb.lock.MongoLock
import io.bluetape4k.testcontainers.storage.MongoDBServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bson.Document
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
open class AutoExtendBackendLeaderElectorBenchmark {

    @Param(
        "local-normal",
        "local-auto-extend",
        "mongo-normal",
        "mongo-auto-extend",
    )
    lateinit var mode: String

    private lateinit var autoExtendMode: AutoExtendBackendMode
    private lateinit var quickElector: LeaderElector
    private lateinit var renewalElector: LeaderElector
    private lateinit var quickLockName: String
    private lateinit var renewalLockName: String

    @Setup
    fun setup() {
        autoExtendMode = AutoExtendBackendMode.from(mode)
        quickLockName = newLockName("blocking-quick")
        renewalLockName = newLockName("blocking-renewal")
        quickElector = createElector(autoExtendMode.quickOptions())
        renewalElector = createElector(autoExtendMode.renewalOptions())

        require(quickElector.runIfLeader("$quickLockName-smoke") { true } == true) {
            "Auto-extend backend benchmark failed blocking quick smoke check. mode=$mode"
        }
        require(renewalElector.runIfLeader("$renewalLockName-smoke") { true } == true) {
            "Auto-extend backend benchmark failed blocking renewal smoke check. mode=$mode"
        }
    }

    @Benchmark
    fun runIfLeader(blackhole: Blackhole) {
        blackhole.consume(quickElector.runIfLeader(quickLockName) { 1 })
    }

    @Benchmark
    fun runIfLeaderWithRenewalWindow(blackhole: Blackhole) {
        blackhole.consume(
            renewalElector.runIfLeader(renewalLockName) {
                TimeUnit.MILLISECONDS.sleep(RENEWAL_ACTION_DWELL_MILLIS)
                1
            },
        )
    }

    private fun createElector(options: LeaderElectionOptions): LeaderElector =
        when (autoExtendMode.backend) {
            AutoExtendBackend.LOCAL -> LocalLeaderElector(options)
            AutoExtendBackend.MONGO -> {
                val db = MongoDBServer.Launcher.getClient().getDatabase("leader_benchmark")
                MongoLeaderElector(
                    db.getCollection(MongoLock.LOCK_COLLECTION_NAME),
                    MongoLeaderElectionOptions(leaderOptions = options),
                )
            }
        }

    private fun newLockName(kind: String): String =
        "auto-extend-bench-$kind-${Base58.randomString(10).lowercase()}"
}

@State(Scope.Benchmark)
open class SuspendAutoExtendBackendLeaderElectorBenchmark {

    @Param(
        "local-normal",
        "local-auto-extend",
        "mongo-normal",
        "mongo-auto-extend",
    )
    lateinit var mode: String

    private lateinit var autoExtendMode: AutoExtendBackendMode
    private lateinit var quickElector: SuspendLeaderElector
    private lateinit var renewalElector: SuspendLeaderElector
    private lateinit var quickLockName: String
    private lateinit var renewalLockName: String

    @Setup
    fun setup() = runBlocking {
        autoExtendMode = AutoExtendBackendMode.from(mode)
        quickLockName = newLockName("suspend-quick")
        renewalLockName = newLockName("suspend-renewal")
        quickElector = createElector(autoExtendMode.quickOptions())
        renewalElector = createElector(autoExtendMode.renewalOptions())

        require(quickElector.runIfLeader("$quickLockName-smoke") { true } == true) {
            "Auto-extend backend benchmark failed suspend quick smoke check. mode=$mode"
        }
        require(renewalElector.runIfLeader("$renewalLockName-smoke") { true } == true) {
            "Auto-extend backend benchmark failed suspend renewal smoke check. mode=$mode"
        }
    }

    @Benchmark
    fun runIfLeader(blackhole: Blackhole) = runBlocking {
        blackhole.consume(quickElector.runIfLeader(quickLockName) { 1 })
    }

    @Benchmark
    fun runIfLeaderWithRenewalWindow(blackhole: Blackhole) = runBlocking {
        blackhole.consume(
            renewalElector.runIfLeader(renewalLockName) {
                delay(RENEWAL_ACTION_DWELL_MILLIS)
                1
            },
        )
    }

    private suspend fun createElector(options: LeaderElectionOptions): SuspendLeaderElector =
        when (autoExtendMode.backend) {
            AutoExtendBackend.LOCAL -> LocalSuspendLeaderElector(options)
            AutoExtendBackend.MONGO -> {
                val db = MongoDBServer.Launcher.getCoroutineClient().getDatabase("leader_benchmark")
                MongoSuspendLeaderElector(
                    db.getCollection<Document>(MongoLock.LOCK_COLLECTION_NAME),
                    MongoLeaderElectionOptions(leaderOptions = options),
                )
            }
        }

    private fun newLockName(kind: String): String =
        "auto-extend-bench-$kind-${Base58.randomString(10).lowercase()}"
}

private enum class AutoExtendBackend {
    LOCAL,
    MONGO,
}

private enum class AutoExtendBackendMode(
    val paramName: String,
    val backend: AutoExtendBackend,
    val autoExtend: Boolean,
) {
    LOCAL_NORMAL("local-normal", AutoExtendBackend.LOCAL, autoExtend = false),
    LOCAL_AUTO_EXTEND("local-auto-extend", AutoExtendBackend.LOCAL, autoExtend = true),
    MONGO_NORMAL("mongo-normal", AutoExtendBackend.MONGO, autoExtend = false),
    MONGO_AUTO_EXTEND("mongo-auto-extend", AutoExtendBackend.MONGO, autoExtend = true),
    ;

    fun quickOptions(): LeaderElectionOptions =
        LeaderElectionOptions(waitTime = 1.seconds, leaseTime = 60.seconds, autoExtend = autoExtend)

    fun renewalOptions(): LeaderElectionOptions =
        LeaderElectionOptions(waitTime = 1.seconds, leaseTime = RENEWAL_LEASE_TIME, autoExtend = autoExtend)

    companion object {
        fun from(paramName: String): AutoExtendBackendMode =
            entries.firstOrNull { it.paramName == paramName }
                ?: error("Unsupported auto-extend backend benchmark mode: $paramName")
    }
}

private val RENEWAL_LEASE_TIME = 90.milliseconds
private const val RENEWAL_ACTION_DWELL_MILLIS = 45L

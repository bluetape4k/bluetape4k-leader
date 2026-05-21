package io.bluetape4k.leader.benchmark

import io.bluetape4k.leader.LeaderElectionOptions
import io.bluetape4k.leader.coroutines.LocalSuspendLeaderElector
import io.bluetape4k.leader.local.LocalAsyncLeaderElector
import io.bluetape4k.leader.local.LocalLeaderElector
import io.bluetape4k.leader.local.LocalVirtualThreadLeaderElector
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Threads
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
open class LocalLeaderElectorBenchmark {

    private lateinit var blockingElector: LocalLeaderElector
    private lateinit var asyncElector: LocalLeaderElector
    private lateinit var asyncOnlyElector: LocalAsyncLeaderElector
    private lateinit var virtualThreadElector: LocalVirtualThreadLeaderElector
    private lateinit var suspendElector: LocalSuspendLeaderElector

    private val counter = AtomicInteger()
    private val directExecutor = Executor { command -> command.run() }

    @Setup
    fun setup() {
        val options = LeaderElectionOptions(
            waitTime = 1.seconds,
            leaseTime = 60.seconds,
        )
        blockingElector = LocalLeaderElector(options)
        asyncElector = LocalLeaderElector(options)
        asyncOnlyElector = LocalAsyncLeaderElector(options)
        virtualThreadElector = LocalVirtualThreadLeaderElector(options)
        suspendElector = LocalSuspendLeaderElector(options)
    }

    @Benchmark
    open fun blockingRunIfLeader(blackhole: Blackhole) {
        val result = blockingElector.runIfLeader("jmh-local-blocking") {
            counter.incrementAndGet()
        }
        blackhole.consume(result)
    }

    @Benchmark
    open fun completableFutureRunIfLeader(blackhole: Blackhole) {
        val result = asyncElector.runAsyncIfLeader("jmh-local-completable", directExecutor) {
            CompletableFuture.completedFuture(counter.incrementAndGet())
        }.join()
        blackhole.consume(result)
    }

    @Benchmark
    open fun asyncOnlyRunIfLeader(blackhole: Blackhole) {
        val result = asyncOnlyElector.runAsyncIfLeader("jmh-local-async-only", directExecutor) {
            CompletableFuture.completedFuture(counter.incrementAndGet())
        }.join()
        blackhole.consume(result)
    }

    @Benchmark
    open fun virtualThreadRunIfLeader(blackhole: Blackhole) {
        val result = virtualThreadElector.runAsyncIfLeader("jmh-local-virtual-thread") {
            counter.incrementAndGet()
        }.await()
        blackhole.consume(result)
    }

    @Benchmark
    open fun suspendRunIfLeader(blackhole: Blackhole) {
        val result = runBlocking {
            suspendElector.runIfLeader("jmh-local-suspend") {
                counter.incrementAndGet()
            }
        }
        blackhole.consume(result)
    }
}

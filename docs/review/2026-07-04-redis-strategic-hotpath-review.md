# Redis Strategic Hot Path 7-Tier Review

Date: 2026-07-04
Scope: issue #574, milestone 0.5.0

## Modules Reviewed

- `leader-core`: local strategic electors and debug-only score formatting.
- `leader-redis-lettuce`: strategic candidate registry lookup path.
- `leader-redis-redisson`: strategic electors and benchmark reference backend.
- `benchmark`: Redis strategic candidate lookup benchmark coverage.

## 7-Tier Result

1. Correctness: PASS
   - Lettuce strategic candidates are registered under exact candidate keys and a lock-scoped node id index.
   - Listing filters missing or stale payloads and removes stale node ids from the index.
   - Election semantics still use the same `ElectionStrategy` inputs and winner checks.

2. API and Contract Compatibility: PASS
   - Public strategic elector APIs are unchanged.
   - Redis key derivation remains internal to the candidate registry implementations.

3. Concurrency and Cancellation: PASS
   - Blocking and suspend registries use the same index shape.
   - No acquisition, release, watchdog, coroutine cancellation, or owner-cleanup contract was changed.

4. Backend Ownership Safety: PASS
   - Lettuce no longer scans Redis keyspace patterns for strategic candidate listing.
   - Lookup is bounded to `SMEMBERS` on the lock-scoped index plus exact candidate-key reads.

5. Tests and Benchmarks: PASS
   - Existing core, Lettuce, and Redisson strategic tests pass.
   - Added JMH benchmark rows for `listCandidates` and `runScoredElection` across Lettuce and Redisson.
   - Captured a local JMH smoke JSON result for the issue evidence.

6. Security and Observability: PASS
   - No credential, token, or backend secret logging changes.
   - Strategic score formatting now happens inside `log.debug {}` lambdas, avoiding eager formatting when debug logging is disabled.

7. Maintainability: PASS
   - Lettuce blocking and suspend registries share the same key/index scheme.
   - Benchmark coverage keeps the Redis strategic lookup path visible for future regressions.

## Validation Evidence

- `./gradlew :bluetape4k-leader-core:compileKotlin :bluetape4k-leader-core:compileTestKotlin :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-redis-redisson:compileTestKotlin :benchmark:compileBenchmarkKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.local.LocalStrategicLeaderElectorTest' --tests 'io.bluetape4k.leader.local.LocalStrategicSuspendLeaderElectorTest' :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicLeaderElectorTest' --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicSuspendLeaderElectorTest' :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonStrategicLeaderElectorTest' --tests 'io.bluetape4k.leader.redisson.RedissonStrategicSuspendLeaderElectorTest' --warning-mode all`
- `./gradlew :benchmark:compileBenchmarkKotlin :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain`
- `java -jar benchmark/build/benchmarks/benchmark/jars/benchmark-benchmark-jmh-0.5.0-JMH.jar '.*RedisStrategicCandidateLookupBenchmark.*' -p candidateCount=16 -bm avgt -tu us -f 1 -wi 0 -i 1 -r 200ms -w 100ms -rf json -rff docs/benchmarks/2026-07-04-issue-574-redis-strategic-lookup-smoke.json`
- `rg -n "ScanArgs|ScanCursor|sync\\.scan|cmds\\.scan" leader-core/src/main leader-redis-lettuce/src/main leader-redis-redisson/src/main benchmark/src/benchmark -g '*.kt'`
- `git diff --check`

## Deferred Verification

Full repository test is intentionally deferred until the complete stacked issue train is implemented, per the requested workflow.

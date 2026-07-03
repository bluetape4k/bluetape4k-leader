# Acquisition Cleanup 7-Tier Review

Date: 2026-07-04
Scope: issues #566 and #567, milestone 0.5.0

## Modules Reviewed

- `leader-consul`: async single and group cleanup callbacks.
- `leader-k8s`: async single/group cleanup callbacks and suspend single acquisition cleanup.
- `leader-exposed-jdbc`: async single and group cleanup callbacks.
- `leader-mongodb`: async single/group cleanup callbacks and suspend single acquisition cleanup.
- `leader-hazelcast`: async single/group cleanup callbacks and suspend single acquisition cleanup.
- `leader-redis-lettuce`: suspend single acquisition cleanup.
- `leader-redis-redisson`: suspend single acquisition cleanup.
- `leader-exposed-r2dbc`: suspend single acquisition cleanup.
- `leader-zookeeper`: suspend single acquisition cleanup.

## 7-Tier Result

1. Correctness: PASS
   - Async cleanup is attached with direct completion callbacks so caller executor shutdown after action start cannot skip release/watchdog close.
   - Suspend electors enter cleanup scope immediately after successful acquisition, before audit, handle, watchdog, or action setup can suspend.

2. API and Contract Compatibility: PASS
   - No public type, constructor, or method signature changes.
   - `CompletableFuture` action result/failure propagation remains delegated to the action future.

3. Concurrency and Cancellation: PASS
   - Coroutine cancellation still propagates.
   - Mandatory release runs under existing `NonCancellable` cleanup blocks.
   - Watchdog cleanup is null-safe when cancellation happens before watchdog creation.

4. Backend Ownership Safety: PASS
   - Existing owner/token/thread-id release checks are preserved.
   - Backend-specific `minLeaseTime` release behavior is unchanged.

5. Tests: PASS
   - Added Consul fake-client async regression where caller executor shutdown cannot skip release/destroy cleanup.
   - Added Kubernetes K3s async regression and source-structure suspend guard.
   - Added Exposed JDBC async regression across H2, PostgreSQL, and MySQL.
   - Added Mongo async and suspend regressions for executor shutdown and `recordAcquired` cancellation.
   - Added Hazelcast async single/group regressions where caller executor is shut down before action future completion.
   - Added Lettuce suspend regression where `recordAcquired` cancellation happens after lock acquisition but before action start, then the next contender reacquires.
   - Added Exposed R2DBC suspend regression across H2, PostgreSQL, and MySQL.
   - Added Redisson and ZooKeeper source-structure guards for immediate post-acquisition cleanup scope.

6. Security and Observability: PASS
   - No new token, credential, or owner payload logging.
   - Existing cleanup failure logging paths are retained.

7. Maintainability: PASS
   - Changes are local to the affected elector patterns.
   - No dependency, module, or public API churn.

## Validation Evidence

- `./gradlew :bluetape4k-leader-consul:compileKotlin :bluetape4k-leader-consul:compileTestKotlin :bluetape4k-leader-k8s:compileKotlin :bluetape4k-leader-k8s:compileTestKotlin :bluetape4k-leader-exposed-jdbc:compileKotlin :bluetape4k-leader-exposed-jdbc:compileTestKotlin :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-mongodb:compileTestKotlin :bluetape4k-leader-hazelcast:compileKotlin :bluetape4k-leader-hazelcast:compileTestKotlin :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-lettuce:compileTestKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-redis-redisson:compileTestKotlin :bluetape4k-leader-exposed-r2dbc:compileKotlin :bluetape4k-leader-exposed-r2dbc:compileTestKotlin :bluetape4k-leader-zookeeper:compileKotlin :bluetape4k-leader-zookeeper:compileTestKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-consul:test --tests 'io.bluetape4k.leader.consul.ConsulLeaderElectorDelegationTest.runAsyncIfLeader cleanup runs after caller executor shutdown' --warning-mode all`
- `./gradlew :bluetape4k-leader-k8s:test --tests 'io.bluetape4k.leader.k8s.KubernetesLeaseSuspendCancellationSafetyTest.suspend elector opens cleanup scope immediately after acquisition' --warning-mode all`
- `./gradlew :bluetape4k-leader-k8s:k8sTest --tests 'io.bluetape4k.leader.k8s.KubernetesLeaseLeaderElectorK3sTest.async cleanup runs after caller executor shutdown' --warning-mode all`
- `./gradlew :bluetape4k-leader-exposed-jdbc:test --tests 'io.bluetape4k.leader.exposed.jdbc.ExposedJdbcLeaderElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 cleanup 이 실행된다' --warning-mode all`
- `./gradlew :bluetape4k-leader-mongodb:test --tests 'io.bluetape4k.leader.mongodb.MongoLeaderElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 cleanup 이 실행된다' --tests 'io.bluetape4k.leader.mongodb.MongoSuspendLeaderElectorTest.runIfLeader - recordAcquired 취소 후에도 lock 이 해제되어 다음 호출이 성공한다' --warning-mode all`
- `./gradlew :bluetape4k-leader-hazelcast:test --tests 'io.bluetape4k.leader.hazelcast.HazelcastLeaderElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 cleanup 이 실행된다' --tests 'io.bluetape4k.leader.hazelcast.HazelcastLeaderGroupElectionTest.runAsyncIfLeader - caller executor shutdown 후 action 완료되어도 그룹 슬롯 cleanup 이 실행된다' --warning-mode all`
- `./gradlew :bluetape4k-leader-hazelcast:test --tests 'io.bluetape4k.leader.hazelcast.HazelcastSuspendCancellationSafetyTest.suspend elector unlock failure handling rethrows CancellationException' --warning-mode all`
- `./gradlew :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceSuspendLeaderElectorTest.runIfLeader - recordAcquired 취소 후에도 lock 이 해제되어 다음 호출이 성공한다' --warning-mode all`
- `./gradlew :bluetape4k-leader-exposed-r2dbc:test --tests 'io.bluetape4k.leader.exposed.r2dbc.ExposedR2DbcSuspendLeaderElectorTest.runIfLeader - recordAcquired 취소 후에도 lock 이 해제되어 다음 호출이 성공한다' --warning-mode all`
- `./gradlew :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonSuspendLeaderElectorTest.suspend elector opens cleanup scope immediately after acquisition' :bluetape4k-leader-zookeeper:test --tests 'io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElectorTest.suspend elector opens cleanup scope immediately after acquisition' --warning-mode all`
- `git diff --check`
- `rg -n "whenCompleteAsync|handleAsync" leader-consul/src/main/kotlin leader-k8s/src/main/kotlin leader-exposed-jdbc/src/main/kotlin leader-mongodb/src/main/kotlin leader-hazelcast/src/main/kotlin` returned no matches.

## Deferred Verification

Full repository test is intentionally deferred until the complete stacked issue train is implemented, per the requested workflow.

# ZooKeeper API 7-Tier Review

Date: 2026-07-04
Scope: issues #571, #572, and #581, milestone 0.5.0

## Modules Reviewed

- `leader-zookeeper`: path construction, CuratorFramework convenience overloads, suspend owner dispatcher lifecycle.
- `leader-consul`: public option validation.

## 7-Tier Result

1. Correctness: PASS
   - ZooKeeper lock names now pass the shared `validateLockName()` contract before becoming znode path segments.
   - Base paths are normalized and validated with Curator path rules before concatenation.

2. API and Contract Compatibility: PASS
   - Existing string-based convenience overloads remain source-compatible and delegate to the typed path overloads.
   - `ZooKeeperElectionPath` provides a single typed argument for new call sites that want to avoid positional string swaps.

3. Concurrency and Cancellation: PASS
   - Suspend single-leader election no longer creates a single-thread executor per `runIfLeader` call.
   - A bounded reusable owner-dispatcher pool preserves Curator's same-thread acquire/release constraint without blocking release behind waiting acquisitions.
   - Cancellation propagation and `NonCancellable` release behavior are preserved.

4. Backend Ownership Safety: PASS
   - Invalid slash, traversal-like, empty-segment, and reserved lock names cannot escape the configured ZooKeeper namespace.
   - Existing valid root and trailing-slash base-path behavior is preserved.

5. Tests: PASS
   - Added invalid ZooKeeper lock/base path regressions.
   - Added typed overload coverage for sync, async, suspend, and group convenience APIs.
   - Added suspend owner-dispatcher reuse coverage and reran the full ZooKeeper module test suite.
   - Added Consul option validation regressions for session name, lease range, and lock delay.

6. Security and Observability: PASS
   - No new token or credential logging.
   - Path validation fails before Curator znode creation, preventing namespace escape attempts.

7. Maintainability: PASS
   - Changes stay within ZooKeeper and Consul API/validation surfaces.
   - Lifecycle ownership is documented on `ZooKeeperSuspendLeaderElector` and its factory.

## Validation Evidence

- `./gradlew :bluetape4k-leader-consul:compileKotlin :bluetape4k-leader-consul:compileTestKotlin :bluetape4k-leader-zookeeper:compileKotlin :bluetape4k-leader-zookeeper:compileTestKotlin --warning-mode all`
- `./gradlew :bluetape4k-leader-consul:test --tests 'io.bluetape4k.leader.consul.ConsulLeaderElectionOptionsTest' :bluetape4k-leader-zookeeper:test --tests 'io.bluetape4k.leader.zookeeper.ZooKeeperApiCoverageTest' --warning-mode all`
- `./gradlew :bluetape4k-leader-zookeeper:test --tests 'io.bluetape4k.leader.zookeeper.ZooKeeperSuspendLeaderElectorTest.SuspendedJobTester - 코루틴 job 경합에서 단일 리더만 실행된다' --warning-mode all`
- `./gradlew :bluetape4k-leader-zookeeper:test --warning-mode all`
- `git diff --check`

## Deferred Verification

Full repository test is intentionally deferred until the complete stacked issue train is implemented, per the requested workflow.

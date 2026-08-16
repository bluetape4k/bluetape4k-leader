# Issue #533 backend diagnostics 구현 계획

> **Agent 작업 지침:** 각 작업을 순서대로 구현할 때 `superpowers:subagent-driven-development`(권장) 또는 `superpowers:executing-plans`를 사용합니다. 진행 상태는 체크박스(`- [ ]`)로 추적합니다.

**목표:** 모든 leader backend가 공통 capability descriptor와 안전한 opt-in connectivity 결과를 Spring Boot와 Ktor에 제공하게 한다.

**아키텍처:** `leader-core`에 immutable diagnostics 모델과 provider SPI를 추가하고, 각 backend elector가 client 수명주기를 재사용해 provider를 구현한다. 기존 Spring/Ktor 상태 endpoint는 유지하며 별도 diagnostics endpoint와 기본 비활성 health/probe 설정을 추가한다.

**기술 스택:** Kotlin 2.3, Java 25, Kotlin Duration, Spring Boot 4.1 Actuator, Ktor 3.x, JUnit 5, MockK, bluetape4k assertions, Gradle, source-backed JSON validator.

---

## 파일 구조

- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt`
- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsTest.kt`
- Create per backend: `leader-*/src/main/kotlin/**/<Backend>Diagnostics.kt`
- Modify: canonical single/group blocking/suspend elector classes in every backend module
- Modify: core listening/tenant decorators and `leader-micrometer` instrumented decorators
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendDiagnosticsEndpoint.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendHealthIndicator.kt`
- Modify: Spring observability properties, selector, auto-configuration imports and metadata
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderBackendDiagnosticsRoute.kt`
- Modify: `LeaderElectionPluginConfig.kt`, `LeaderElectionPlugin.kt`
- Modify: backend provider tests, Spring/Ktor tests, README locale set, capability manifest validator
- Create: `docs/review/2026-08-16-issue-533-backend-diagnostics-review.md`
- Create: `docs/lessons/2026-08-16-issue-533-backend-diagnostics.md`

## Task 1: Core diagnostics contract

**파일:**
- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnosticsTest.kt`
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LeaderBackendDiagnostics.kt`

- [ ] **Step 1: Write RED tests**

Add tests that assert:

```kotlin
val provider = RecordingProvider()
provider.diagnostics().connectivity.status shouldBeEqualTo LeaderBackendConnectivityStatus.NOT_CHECKED
provider.calls shouldBeEqualTo 0
provider.diagnostics(probe = true, timeout = 250.milliseconds).connectivity.status shouldBeEqualTo
    LeaderBackendConnectivityStatus.UP
provider.calls shouldBeEqualTo 1
```

Also assert blank backend IDs, duplicate/blank limitations, negative latency, and non-positive or infinite timeout are rejected.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.diagnostics.LeaderBackendDiagnosticsTest" --no-daemon
```

예상 결과: diagnostics 타입이 없으므로 컴파일에 실패한다.

- [ ] **Step 3: Implement minimal immutable model and provider default**

Implement the exact types in the approved design. `diagnostics(probe=false)` must use `LeaderBackendConnectivity.notChecked()`; `probe=true` must validate timeout and call `checkConnectivity` exactly once.

- [ ] **Step 4: Verify GREEN**

Run the focused core test and then `:bluetape4k-leader-core:test`.

예상 결과: PASS.

- [ ] **Step 5: Commit**

Commit the core contract and test with Lore trailers; record the RED and GREEN commands in `Tested:`.

## Task 2: Local provider and decorator preservation

**파일:**
- Create: `leader-core/src/main/kotlin/io/bluetape4k/leader/diagnostics/LocalLeaderBackendDiagnostics.kt`
- Modify: local single/group blocking/async/suspend/virtual elector base classes
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/ListeningLeaderElectors.kt`
- Modify: `leader-core/src/main/kotlin/io/bluetape4k/leader/TenantScopedLeaderElectors.kt`
- Modify: coroutine listening/tenant equivalents
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/diagnostics/LocalLeaderBackendDiagnosticsTest.kt`

- [ ] **Step 1: Write RED tests for Local descriptor and wrapper forwarding**

Assert Local reports all four execution models, single/group support, process clock, client lease TTL, and `UP`. Wrap the provider with listening and tenant-scoped decorators and assert the descriptor remains equal.

- [ ] **Step 2: Verify RED**

Run the two diagnostics tests; expect missing Local provider/forwarding.

- [ ] **Step 3: Implement Local provider and forwarding**

Use one immutable `LocalLeaderBackendDiagnostics` object. Add `LeaderBackendDiagnosticsAware` with nullable `backendDiagnosticsProvider`; wrappers implement this carrier and return the delegate when it is a provider or the nested carrier value otherwise. Canonical electors implement `LeaderBackendDiagnosticsProvider` directly. This prevents wrappers around user implementations from advertising a provider that does not exist.

- [ ] **Step 4: Verify GREEN**

Core 테스트를 실행한다. 예상 결과: leader 동작 변경 없이 PASS.

- [ ] **Step 5: Commit**

Commit Local and decorator changes with Lore trailers.

## Task 3: External backend descriptors and passive probes

**파일:**
- Create: `<Backend>LeaderBackendDiagnostics.kt` in Lettuce, Redisson, Exposed JDBC/R2DBC, MongoDB, DynamoDB, etcd, Consul, Kubernetes, Hazelcast, ZooKeeper modules
- Modify: each module's canonical single/group blocking/suspend/virtual elector classes
- Test: one `<Backend>LeaderBackendDiagnosticsTest.kt` per module

- [ ] **Step 1: Write backend RED tests**

For every module, assert exact backend ID, execution model sets, single/group lease-extension and audit-state support, clock source, TTL mode, limitations, and connectivity mapping. Use mocks only for passive client state; do not verify SDK ping calls.

- [ ] **Step 2: Verify RED in bounded batches**

Run:

```bash
./gradlew :bluetape4k-leader-redis-lettuce:test :bluetape4k-leader-redis-redisson:test --no-daemon
./gradlew :bluetape4k-leader-exposed-jdbc:test :bluetape4k-leader-exposed-r2dbc:test :bluetape4k-leader-mongodb:test --no-daemon
./gradlew :bluetape4k-leader-dynamodb:test :bluetape4k-leader-etcd:test :bluetape4k-leader-consul:test --no-daemon
./gradlew :bluetape4k-leader-k8s:test :bluetape4k-leader-hazelcast:test :bluetape4k-leader-zookeeper:test --no-daemon
```

예상 결과: provider가 없으므로 새 테스트가 각각 실패한다.

- [ ] **Step 3: Implement shared immutable descriptor per backend**

Electors delegate the SPI to the module provider. Passive probes may inspect only existing client lifecycle/connection state. Exposed, MongoDB, DynamoDB, etcd, and Consul return `UNKNOWN` until a safe bounded client method exists.

- [ ] **Step 4: Verify GREEN per batch**

각 batch를 다시 실행한다. 예상 결과: PASS. Testcontainers 테스트는 기존 fixture를 사용할 수 있지만 diagnostics 테스트 자체는 새 container를 요구하지 않아야 한다.

- [ ] **Step 5: Commit**

Commit backend providers in reviewable batches with Lore trailers.

## Task 4: Spring endpoint and health indicator

**파일:**
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendDiagnosticsEndpointTest.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendHealthIndicatorTest.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendDiagnosticsEndpoint.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderBackendHealthIndicator.kt`
- Modify: observability properties, selector, auto-configuration, imports, configuration metadata

- [ ] **Step 1: Write Spring RED tests**

Assert the new endpoint returns `NOT_CHECKED` without invoking probe, health is absent by default, explicit enable calls the selected provider once with configured timeout, and statuses map to Spring `UP`, `DOWN`, or `UNKNOWN`. Assert contexts without a provider do not register either bean.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test --tests "*LeaderBackend*" --no-daemon
```

예상 결과: endpoint/health 타입과 속성이 없으므로 실패한다.

- [ ] **Step 3: Implement Spring adapters**

Use the state selector's chosen elector and resolve either `LeaderBackendDiagnosticsProvider` or `LeaderBackendDiagnosticsAware.backendDiagnosticsProvider`. Do not change `LeaderElectionStatusResponse` or the existing `leaderElection` endpoint. Add metadata for endpoint enablement, health enablement, and timeout.

- [ ] **Step 4: Verify GREEN**

Spring Boot 테스트 전체를 실행한다. 예상 결과: PASS이며 기존 endpoint 응답 테스트는 변경되지 않는다.

- [ ] **Step 5: Commit**

Commit Spring changes with Lore trailers.

## Task 5: Ktor diagnostics route

**파일:**
- Create: `leader-ktor/src/test/kotlin/io/bluetape4k/leader/ktor/LeaderBackendDiagnosticsRouteTest.kt`
- Create: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderBackendDiagnosticsRoute.kt`
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPluginConfig.kt`
- Modify: `leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`

- [ ] **Step 1: Write Ktor RED tests**

Assert default 404, enabled static route returns descriptor plus `NOT_CHECKED`, connectivity enable invokes probe exactly once with configured timeout, custom path works, and enabling diagnostics with an elector that lacks the SPI fails plugin installation.

- [ ] **Step 2: Verify RED**

Run:

```bash
./gradlew :bluetape4k-leader-ktor:test --tests "*LeaderBackendDiagnosticsRouteTest" --no-daemon
```

예상 결과: config와 route 타입이 없으므로 실패한다.

- [ ] **Step 3: Implement route and stable JSON encoding**

Reuse the existing JSON escape helper or extract one internal helper. Preserve the existing management route response byte-for-byte.

- [ ] **Step 4: Verify GREEN**

Ktor 테스트 전체를 실행한다. 예상 결과: PASS.

- [ ] **Step 5: Commit**

Commit Ktor changes with Lore trailers.

## Task 6: Manifest and documentation parity

**파일:**
- Modify: `scripts/ci/leader-contract-capabilities.json`
- Modify: `scripts/ci/validate_leader_contract_matrix.py`
- Modify: `scripts/ci/validate_leader_contract_matrix_test.py`
- Modify: `README.md`, `README.ko.md`, `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`, `leader-ktor/README.md`, `leader-ktor/README.ko.md`

- [ ] **Step 1: Write validator RED tests**

Add a fixture with a missing runtime diagnostics source and assert the validator emits `runtime diagnostics source anchor is missing`. Add a locale drift assertion for diagnostics prose markers.

- [ ] **Step 2: Verify RED**

Run `python3 -m unittest scripts.ci.validate_leader_contract_matrix_test`; expect the new assertion to fail.

- [ ] **Step 3: Add source anchors and EN/KO documentation**

Document static versus active checks, default-off behavior, `UNKNOWN` meaning, timeout, security exposure, and the separate Spring/Ktor paths. Keep the existing capability table generated from the manifest.

- [ ] **Step 4: Verify GREEN**

Run the validator self-test, unit test, README language/link checks, and `git diff --check`.

- [ ] **Step 5: Commit**

Commit manifest and documentation with Lore trailers.

## Task 7: Fresh verification, 7-tier review, and PR

**파일:**
- Create: `docs/review/2026-08-16-issue-533-backend-diagnostics-review.md`
- Create: `docs/lessons/2026-08-16-issue-533-backend-diagnostics.md`

- [ ] **Step 1: Run fresh verification**

Run targeted module tests, `./gradlew detekt`, capability validator tests, README validators, and `git diff --check`. Re-run failed commands after fixes; do not reuse baseline evidence.

- [ ] **Step 2: Run independent 7-tier review**

Review correctness/API, concurrency/cancellation, performance, security/privacy, operations, tests, and docs/release readiness. Retry a stalled lane once; if it stalls again, perform that perspective inline. P0/P1 must be zero.

- [ ] **Step 3: Record review and lesson**

Write Korean review/lesson files with exact commands, findings, repairs, remaining risks, and reviewer evidence.

- [ ] **Step 4: Push and create PR**

Push `feat/epic-obs-01-diagnostics` and create a Korean PR against `develop`. Assign `debop`, copy Issue #533 milestone and applicable labels, link Epic #699 and downstream #559, and end the body with `## DoD Status`.

- [ ] **Step 5: Read back live PR state**

Verify exact head, base, assignee, milestone, labels, CI/checks, reviews/threads, and mergeability. Stop at merge-ready reporting; merge requires a fresh explicit approval.

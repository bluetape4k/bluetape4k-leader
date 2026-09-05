# Issue #884 Testcontainers readiness 경계 진단 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Testcontainers HTTP wait 실패가 target container를 제거하기 전에 internal endpoint, mapped host endpoint, Docker port mapping을 함께 수집해 Colima host forwarding과 container service 실패를 결정적으로 구분한다.

**Architecture:** `leader-core` test fixtures에 기존 `WaitStrategy`를 감싸는 진단 strategy와 bounded probe를 둔다. 정상 경로는 기존 delegate만 실행하고, 실패 경로에서 pinned Alpine helper를 target network namespace에 붙여 internal HTTP를 확인한 뒤 host HTTP와 Docker inspect를 한 진단으로 합친다. Production source, startup timeout, Colima runtime은 변경하지 않는다.

**Tech Stack:** Kotlin/JVM, JUnit 5, MockK, bluetape4k assertions, Testcontainers 2.0.5, Docker Java, Gradle test fixtures, Colima.

---

## 실행 상태

- [x] Task 1 — 기준 상태와 root-cause 경계 고정
- [x] Task 2 — classifier와 wait wrapper RED
- [x] Task 3 — 최소 diagnostic fixture GREEN
- [x] Task 4 — Toxiproxy·etcd 적용과 실제 endpoint proof
- [x] Task 5 — clean 반복·module·전체 build 검증
- [x] Task 6 — lesson·inline review·delivery checkpoint

## Task 1 — 기준 상태와 root-cause 경계 고정

**Files:**
- Create: `docs/superpowers/specs/2026-09-06-issue-884-testcontainers-readiness-design.md`
- Create: `docs/superpowers/plans/2026-09-06-issue-884-testcontainers-readiness-plan.md`

- [x] **Step 1: Exact base와 live issue를 고정한다**

  `origin/develop`과 worktree base가 `65731c0b4a0f046bae4c85a97ee4646c95d27ee1`인지 확인하고 Issue #884의 milestone `1.1.0`, assignee `debop`, labels `bug`, `ci`, `test`, `build`를 읽는다.

- [x] **Step 2: Clean baseline을 순차 실행한다**

  ```bash
  ./gradlew :bluetape4k-leader-redis-lettuce:cleanTest :bluetape4k-leader-redis-lettuce:test --tests 'io.bluetape4k.leader.lettuce.LettuceStrategicGroupToxiproxyCancellationTest' --no-build-cache --rerun-tasks
  ./gradlew :bluetape4k-leader-redis-redisson:cleanTest :bluetape4k-leader-redis-redisson:test --tests 'io.bluetape4k.leader.redisson.RedissonStrategicGroupToxiproxyCancellationTest' --no-build-cache --rerun-tasks
  ./gradlew :bluetape4k-leader-etcd:cleanTest :bluetape4k-leader-etcd:test --no-build-cache --rerun-tasks
  ```

  Expected: Lettuce 2/2, Redisson 3/3, etcd 156/156, failures/errors/skips 0.

- [x] **Step 3: Dependency source에서 증거 소실 지점을 확인한다**

  Testcontainers `2.0.5`의 `GenericContainer.tryStart()`가 `waitUntilContainerStarted()` 실패 후 target을 제거하는지, Toxiproxy `/version`과 etcd `/health`가 mapped host `HttpWaitStrategy`를 사용하는지 확인한다.

- [x] **Step 4: Internal probe feasibility를 확인한다**

  target image 안에는 `/bin/sh`가 없음을 확인하고, pinned Alpine helper를 `container:<target-id>` network mode로 실행해 Toxiproxy `/version`과 etcd `/health` 응답을 각각 얻는다.

## Task 2 — classifier와 wait wrapper RED

**Files:**
- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/testcontainers/ReadinessBoundaryWaitStrategyTest.kt`
- Modify: `leader-core/build.gradle.kts`

- [x] **Step 1: Test fixture compile dependency만 추가한다**

  `leader-core/build.gradle.kts`에 기존 catalog alias를 재사용한다. 공개 fixture signature가 Testcontainers 타입을 노출하므로 최종 구현은 소비 모듈 compile classpath를 보존하는 `testFixturesApi`를 사용한다.

  ```kotlin
  testFixturesApi(libs.testcontainers)
  ```

  새 artifact/version alias는 추가하지 않는다.

- [x] **Step 2: 분류 RED를 작성한다**

  `ReadinessBoundaryDiagnostic.classify(...)`의 wished-for API로 다음 독립 테스트를 작성한다.

  ```kotlin
  internalSuccess && hostFailure && mappingPresent -> HOST_FORWARDING
  internalFailure -> CONTAINER_SERVICE
  mappingMissing -> PORT_MAPPING
  incompleteEvidence -> UNKNOWN
  ```

  관찰값은 success Boolean과 bounded detail만 가진다. public test-fixture data class는 `Serializable`과 `serialVersionUID`를 정의한다.

- [x] **Step 3: Wait wrapper RED를 작성한다**

  fake delegate와 fake collector를 사용해 다음을 검증한다.

  ```kotlin
  delegate success -> collector invocation count == 0
  delegate failure -> ContainerLaunchException cause === original failure
  delegate failure -> exception message contains boundary, internal, host, mapping
  withStartupTimeout -> delegate receives the same duration
  ```

  exception은 `io.bluetape4k.assertions.assertFailsWith`로 검증한다.

- [x] **Step 4: RED를 실행한다**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.testcontainers.ReadinessBoundaryWaitStrategyTest' --no-build-cache --rerun-tasks --console=plain
  ```

  Expected: missing fixture symbols 때문에 test compilation이 실패한다. 테스트 오타나 dependency resolution 오류는 유효한 RED가 아니다.

## Task 3 — 최소 diagnostic fixture GREEN

**Files:**
- Create: `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/testcontainers/ReadinessBoundaryWaitStrategy.kt`
- Test: `leader-core/src/test/kotlin/io/bluetape4k/leader/testcontainers/ReadinessBoundaryWaitStrategyTest.kt`

- [x] **Step 1: 진단 model과 classifier를 구현한다**

  `ReadinessFailureBoundary`, `ReadinessProbeObservation`, `ReadinessBoundaryDiagnostic`을 test-fixtures variant에 둔다. 분류 우선순위는 `PORT_MAPPING`, `CONTAINER_SERVICE`, `HOST_FORWARDING`, `UNKNOWN` 순이다. detail은 줄바꿈을 공백으로 바꾸고 256자로 제한한다.

- [x] **Step 2: Delegate wrapper를 구현한다**

  `ReadinessBoundaryWaitStrategy`는 `WaitStrategy`를 구현한다. `waitUntilReady()` 성공 시 즉시 반환하고 실패 시 collector를 한 번 호출해 diagnostic message를 가진 새 `ContainerLaunchException`을 던지되 원래 throwable을 cause로 보존한다. `withStartupTimeout()`은 같은 duration을 delegate에 전달하고 자기 자신을 반환한다.

- [x] **Step 3: Docker boundary collector를 구현한다**

  host probe는 JDK `HttpURLConnection`으로 connect/read 각 2초, internal probe는 다음 immutable image와 one-shot 5초 상한을 사용한다.

  ```text
  alpine@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc
  wget -qO- -T 2 http://127.0.0.1:<port><path>
  ```

  Docker inspect는 target 상태와 요청한 port의 binding만 정규화한다. helper는 `use` 또는 `try/finally`에서 중지한다. probe failure는 원래 wait failure를 덮지 않고 observation detail로 축약한다.

- [x] **Step 4: GREEN과 fixture compilation을 확인한다**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.testcontainers.ReadinessBoundaryWaitStrategyTest' --no-build-cache --rerun-tasks --console=plain
  ./gradlew :bluetape4k-leader-core:testFixturesJar --no-build-cache --rerun-tasks --console=plain
  ```

  Expected: 모든 classifier/wrapper test 통과, compile warning/error 0.

- [x] **Step 5: 첫 구현 commit을 만든다**

  Kotlin test, fixture, `leader-core/build.gradle.kts`만 Korean Lore commit으로 묶는다.

## Task 4 — Toxiproxy·etcd 적용과 실제 endpoint proof

**Files:**
- Modify: `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceStrategicGroupToxiproxyCancellationTest.kt`
- Modify: `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonStrategicGroupToxiproxyCancellationTest.kt`
- Modify: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/AbstractEtcdLeaderTest.kt`
- Create: `leader-etcd/src/test/kotlin/io/bluetape4k/leader/etcd/EtcdReadinessBoundaryIntegrationTest.kt`

- [x] **Step 1: Toxiproxy 적용점을 바꾼다**

  두 cancellation class가 per-test `ToxiproxyServer`를 시작하기 전에 `readinessBoundaryWaitStrategy(containerPort = 8474, path = "/version")`를 설정한다. Redis/network/toxic/action lifecycle은 수정하지 않는다.

- [x] **Step 2: Etcd launcher 적용점을 바꾼다**

  `AbstractEtcdLeaderTest`의 shared `EtcdServer`를 직접 생성하고 `/health` diagnostic wait를 설정한 뒤 한 번 시작해 `ShutdownQueue`에 등록한다. external wrapper의 endpoint, reuse=false, client ownership 계약을 유지한다.

- [x] **Step 3: 실제 endpoint integration proof를 작성한다**

  bounded integration test는 Toxiproxy 또는 etcd 정상 시작에서 delegate가 성공하고 diagnostic exception이 발생하지 않음을 검증한다. 실제 Colima failure를 인위적인 sleep, network restart, timeout 연장으로 만들지 않는다.

- [x] **Step 4: 대상 module을 한 번 clean 실행한다**

  Task 1의 세 command를 같은 순서로 실행한다. Expected: Lettuce 2, Redisson 3, etcd 157개 이상, failures/errors/skips 0.

- [x] **Step 5: 적용 commit을 만든다**

  세 module의 test-only 적용과 integration proof만 Korean Lore commit으로 묶는다.

## Task 5 — clean 반복·module·전체 build 검증

**Files:**
- Verify: all changed files and generated JUnit XML

- [x] **Step 1: Source hygiene를 검사한다**

  ```bash
  git diff --check
  rg -n 'assertThrows|kotlin\.test\.assertFailsWith|invoking \{.*shouldThrow|Thread\.sleep\(' leader-core/src/test leader-core/src/testFixtures leader-redis-lettuce/src/test leader-redis-redisson/src/test leader-etcd/src/test
  ```

  Expected: `git diff --check` exit 0, 새 금지 assertion/sleep 0건.

- [x] **Step 2: Clean startup matrix를 5회 순차 반복한다**

  각 iteration에서 `cleanTest`, `--no-build-cache`, `--rerun-tasks`를 사용하고 서로 다른 Gradle process를 병렬 실행하지 않는다. JUnit XML 합계에서 expected tests, failure=0, error=0, skipped=0을 매회 확인한다.

- [x] **Step 3: Affected module과 static analysis를 실행한다**

  ```bash
  ./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-redis-lettuce:test :bluetape4k-leader-redis-redisson:test :bluetape4k-leader-etcd:test --no-build-cache --rerun-tasks --console=plain
  ./gradlew detekt --no-build-cache --rerun-tasks --console=plain
  ```

- [x] **Step 4: 전체 build와 binary API 무변경을 확인한다**

  ```bash
  ./gradlew build --no-build-cache --rerun-tasks --console=plain
  git diff --exit-code -- '**/api/*.api'
  ```

  Expected: build 성공, production ABI dump diff 0.

## Task 6 — lesson·inline review·delivery checkpoint

**Files:**
- Create: `docs/lessons/2026-09-06-issue-884-testcontainers-readiness.md`
- Create: `docs/review/2026-09-06-issue-884-testcontainers-readiness-review.md`

- [x] **Step 1: 재사용 lesson을 기록한다**

  간헐적 Testcontainers HTTP wait에서 retry pass를 해결로 보지 않는 이유, target 제거 전 세 경계를 수집하는 방법, distroless image의 probe 제한, 정상 Colima 재시작 금지를 기록한다.

- [x] **Step 2: Exact diff inline review를 수행한다**

  Kotlin/test infrastructure, correctness, lifecycle/cleanup, security/redaction, performance/boundedness, API/ABI, CI/test determinism 관점으로 file/line 증거를 검토한다. P0/P1은 모두 고치고 targeted/broader validation을 다시 실행한다.

- [x] **Step 3: GNO를 갱신하고 final local commit을 만든다**

  ```bash
  gno update
  gno embed --collection bluetape4k-docs
  gno search 'bluetape4k-leader Testcontainers readiness boundary' -c bluetape4k-docs
  ```

  lesson, review, 최종 검증 증거를 Korean Lore commit으로 묶는다.

- [x] **Step 4: Delivery gate에서 멈춘다**

  Exact local HEAD, commits, changed files, test counts, P0/P1, remaining risks를 보고한다. PR 생성은 repository `bluetape4k/bluetape4k-leader`, base `develop`, head `fix/issue-884-testcontainers-readiness`에 대한 별도 권한이 확인될 때까지 `PENDING`이다. Merge는 PR CI와 fresh exact-head 승인 전까지 실행하지 않는다.

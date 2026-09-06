# Issue #856 custom backend conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 외부 custom backend가 네 strategic API 변형의 후보 lifecycle, concurrency, expiry, winner 계약을 같은 fixture로 검증하게 한다.

**Architecture:** `leader-core` testFixtures에 provider와 blocking/suspend adapter 계약을 추가하고, abstract JUnit 5 fixture가 single/group mode에 공통 assertion을 실행한다. `leader-core`의 test-only custom provider가 fixture 적용을 증명하며 production API와 dependency는 변경하지 않는다.

**Tech Stack:** Kotlin, JUnit 5, kotlinx-coroutines-test, bluetape4k assertions/concurrency helpers, Gradle testFixtures

---

## 파일 구조

- Create `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/StrategicBackendConformance.kt`: provider와 blocking/suspend adapter API, mode 이름, lifecycle KDoc.
- Create `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractStrategicBackendConformanceTest.kt`: 네 mode의 lifecycle, expiry, concurrency, winner 공통 테스트.
- Create `leader-core/src/test/kotlin/io/bluetape4k/leader/contract/CustomStrategicBackendConformanceTest.kt`: built-in Local 구현과 독립적인 custom in-memory provider.
- Modify `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonStrategicHeartbeatExpirationRaceTest.kt`: `TTL <= 0`이 아니라 실제 key 부재까지 대기.
- Modify `leader-core/README.md`: fixture 적용 예제와 execution-model capability matrix.
- Modify `leader-core/README.ko.md`: 영문 문서와 같은 계약을 자연스러운 한국어로 설명.
- Create `docs/review/2026-09-06-issue-856-custom-backend-conformance-review.md`: exact diff 인라인 6관점 리뷰와 검증 근거.
- Create `docs/lessons/2026-09-06-issue-856-custom-backend-conformance.md`: expiry 경계와 public test fixture 설계 교훈.

### Task 1: Provider API와 RED compile test

**Complexity:** 중간. published testFixtures의 additive source API다.

**Files:**
- Create: `leader-core/src/test/kotlin/io/bluetape4k/leader/contract/CustomStrategicBackendConformanceTest.kt`
- Create: `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/StrategicBackendConformance.kt`

- [ ] **Step 1: fixture를 상속하는 실패 테스트 작성**

```kotlin
class CustomStrategicBackendConformanceTest : AbstractStrategicBackendConformanceTest() {
    override fun createProvider(): StrategicBackendConformanceProvider = CustomProvider()
}
```

- [ ] **Step 2: RED 확인**

Run: `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.contract.CustomStrategicBackendConformanceTest"`

Expected: `AbstractStrategicBackendConformanceTest` 또는 `StrategicBackendConformanceProvider` unresolved reference로 compile 실패.

- [ ] **Step 3: 최소 provider/adapter 계약 추가**

```kotlin
interface StrategicBackendConformanceProvider : AutoCloseable {
    fun blockingSingle(nodeId: String): BlockingStrategicBackend
    fun blockingGroup(nodeId: String): BlockingStrategicBackend
    fun suspendSingle(nodeId: String): SuspendStrategicBackend
    fun suspendGroup(nodeId: String): SuspendStrategicBackend
    fun expireCandidate(mode: StrategicBackendMode, lockName: String, nodeId: String)
    fun clear(mode: StrategicBackendMode, lockName: String)
}
```

blocking/suspend adapter는 `registerCandidate`, `refreshCandidate`, `unregisterCandidate`,
`listCandidates`, `updateResult`, `runIfLeader`를 제공한다. `close()` 기본 구현은 no-op으로
두되 fixture가 test마다 정확히 한 번 호출한다.

- [ ] **Step 4: testFixtures compile 확인**

Run: `./gradlew :bluetape4k-leader-core:testFixturesJar`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: 설계/계획 commit**

```bash
git add docs/superpowers/specs/2026-09-06-issue-856-custom-backend-conformance-design.md \
  docs/superpowers/plans/2026-09-06-issue-856-custom-backend-conformance-plan.md
git commit
```

Commit은 Lore protocol과 한국어 intent line을 사용한다.

### Task 2: 공통 lifecycle·winner fixture TDD

**Complexity:** 높음. 네 mode가 같은 의미를 공유해야 한다.

**Files:**
- Create: `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractStrategicBackendConformanceTest.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/contract/CustomStrategicBackendConformanceTest.kt`

- [ ] **Step 1: lifecycle와 winner assertion 추가**

각 mode에 다음 동작을 추가한다.

```kotlin
backend.registerCandidate(lockName, first, 30.seconds)
backend.refreshCandidate(lockName, refreshed, 60.seconds)
backend.updateResult(lockName, first.nodeId, CandidateResult.SUCCESS)
backend.unregisterCandidate(lockName, first.nodeId)
backend.unregisterCandidate(lockName, first.nodeId)
backend.listCandidates(lockName).shouldBeEmpty()
```

FIFO winner의 action count는 1, loser action count는 0, loser 결과는 `null`이어야 한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.contract.CustomStrategicBackendConformanceTest"`

Expected: custom provider의 미구현 연산 또는 잘못된 final state로 실패.

- [ ] **Step 3: custom shared store와 네 adapter 구현**

`ConcurrentHashMap`의 `compute`/`computeIfPresent`를 사용한다. refresh는 metadata만
교체하고, update는 `CandidateInfo.withResult`를 적용하며, unregister와 expiry는 entry를
제거한다. single/group namespace를 분리한다.

- [ ] **Step 4: GREEN 확인**

Run: `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.contract.CustomStrategicBackendConformanceTest"`

Expected: lifecycle/winner test 전부 PASS.

### Task 3: concurrency·expiry fixture TDD

**Complexity:** 높음. atomicity와 linearizability 경계를 검증한다.

**Files:**
- Modify: `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractStrategicBackendConformanceTest.kt`
- Modify: `leader-core/src/test/kotlin/io/bluetape4k/leader/contract/CustomStrategicBackendConformanceTest.kt`

- [ ] **Step 1: 실패 가능한 경합 테스트 추가**

blocking은 `MultithreadingTester`, suspend는 `coroutineScope`와 `async`를 사용해 다음을
검증한다.

```kotlin
repeat(100) {
    updateResult(lockName, nodeId, CandidateResult.SUCCESS)
    refreshCandidate(lockName, heartbeat, 60.seconds)
}
```

최종 `successCount`는 정확히 100이고 metadata는 heartbeat 값이어야 한다. concurrent
refresh/unregister가 모두 끝난 뒤 후보는 없어야 한다.

- [ ] **Step 2: RED 확인**

Run: `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.contract.CustomStrategicBackendConformanceTest"`

Expected: non-atomic custom store 변형에서 lost update 또는 resurrection으로 실패.

- [ ] **Step 3: 원자 store 연산으로 최소 수정**

같은 `(mode, lockName, nodeId)` key의 mutation은 `ConcurrentHashMap.compute` 계열 한 번으로
완료한다. 별도 read-modify-write를 추가하지 않는다.

- [ ] **Step 4: GREEN 및 반복 실행**

Run: `./gradlew :bluetape4k-leader-core:test --tests "io.bluetape4k.leader.contract.CustomStrategicBackendConformanceTest" --rerun-tasks`

Expected: 모든 concurrency/expiry test PASS, hang 없음.

### Task 4: Redisson expiry 전제 안정화

**Complexity:** 낮음. production source는 변경하지 않는다.

**Files:**
- Modify: `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redisson/RedissonStrategicHeartbeatExpirationRaceTest.kt`

- [ ] **Step 1: 기존 RED 증거 고정**

2026-09-06 #884 exact-base full build에서 suspend group case가 `TTL == 0` 뒤 fresh
`CandidateInfo`를 관찰해 실패했고, 단독 재실행은 PASS했다. 이는 실제 key 제거보다 먼저
gate를 해제한 timing failure다.

- [ ] **Step 2: 실제 부재까지 기다리도록 수정**

```kotlin
withTimeout(5.seconds) {
    while (realCache.get(nodeId) != null) {
        delay(10)
    }
}
```

- [ ] **Step 3: Redisson targeted 반복 검증**

Run: `./gradlew :bluetape4k-leader-redis-redisson:test --tests "io.bluetape4k.leader.redisson.RedissonStrategicHeartbeatExpirationRaceTest" --rerun-tasks`

Expected: 4 tests PASS, key 부재 assertion PASS.

### Task 5: README와 public KDoc 동기화

**Complexity:** 중간. 공개 testFixtures 사용법과 capability 오해를 방지한다.

**Files:**
- Modify: `leader-core/README.md`
- Modify: `leader-core/README.ko.md`
- Modify: `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/StrategicBackendConformance.kt`
- Modify: `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractStrategicBackendConformanceTest.kt`

- [ ] **Step 1: capability matrix와 적용 예제 작성**

README 두 locale에 strategic blocking/suspend는 새 fixture를 사용하고 lock 기반 async와
virtual-thread는 각 실행 모델 fixture를 사용한다고 기록한다. provider가 credential,
client, namespace, expiry control, cleanup을 소유하고 fixture 통과가 성능·장애 복구를
보증하지 않는다고 명시한다.

- [ ] **Step 2: Korean KDoc과 locale 의미 대조**

API 이름, method, mode 수, 미보증 범위가 source와 두 README에서 같아야 한다.

- [ ] **Step 3: 문서 검사**

Run: `node ~/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs leader-core/README.ko.md docs/superpowers/specs/2026-09-06-issue-856-custom-backend-conformance-design.md docs/superpowers/plans/2026-09-06-issue-856-custom-backend-conformance-plan.md`

Expected: unresolved terminology finding 0.

### Task 6: 검증·인라인 리뷰·lesson·PR

**Complexity:** 높음. exact head delivery gate다.

**Files:**
- Create: `docs/review/2026-09-06-issue-856-custom-backend-conformance-review.md`
- Create: `docs/lessons/2026-09-06-issue-856-custom-backend-conformance.md`

- [ ] **Step 1: module 및 static/API 검증**

Run sequentially:

```bash
./gradlew :bluetape4k-leader-core:test
./gradlew :bluetape4k-leader-redis-redisson:test
./gradlew detekt checkBinaryCompatibility
git diff --check
```

Expected: 각 command exit 0, 실패 test 0, unclassified binary incompatibility 0.

- [ ] **Step 2: spec/plan 추적 검증**

Issue 수용 기준을 code/test/docs와 일대일로 대조한다. production API, dependency,
module/workflow 변경이 없음을 diff에서 확인한다.

- [ ] **Step 3: exact diff 인라인 6관점 리뷰**

성능, 안정성, 보안, 운영, 개발자/API, 사용자/caller 관점으로 각각 P0/P1/P2/P3를
기록하고 통합한다. 현재 조건상 독립 provenance를 주장하지 않는다. P0/P1은 수정 후
해당 관점과 targeted test를 다시 실행한다.

- [ ] **Step 4: lesson과 Lore commit**

lesson에 context, decision, observed #884 timing failure, outcome, verification, future guard를
기록한다. 모든 변경을 한국어 intent line과 Lore trailer로 commit한다.

- [ ] **Step 5: PR 생성과 exact-head CI 확인**

`feat/issue-856-custom-backend-conformance`를 push하고 `develop` base의 한국어 PR을 만든다.
PR body 마지막 heading은 `## DoD Status`다. live metadata, head SHA, checks, reviews,
threads, mergeability를 다시 읽고 CI 완료까지 기다린다. merge는 세 PR의 fresh bundle
승인 전 수행하지 않는다.

## 위험 예측과 rollback

| 위험 | 신호 | 완화 | rollback/rerun |
|---|---|---|---|
| fixture가 backend 구현 세부를 강제 | Redis/DB adapter가 admin API 없이는 구현 불가 | expiry/clear만 provider test control로 분리 | adapter API를 축소하고 spec review 재실행 |
| concurrency test flake | 단독 PASS, module build FAIL | bounded worker, 실제 completion wait, sleep 최소화 | raw failure 보존 후 harness 원인 수정, Task 3부터 재실행 |
| published test API 과다 | consumer가 불필요한 helper를 구현 | 최소 operation surface와 default close | public type 추가를 되돌리고 package-private assertion 대안 재검토 |
| Redisson polling 부하 | Redis RTT 과다 또는 timeout | 한 candidate key만 10ms bounded poll | targeted test 로그 확인 후 poll condition 조정 |

## 수용 기준 → Task 추적

| 수용 기준 | Task |
|---|---|
| 실행 모델 최소 계약 | 1, 2, 5 |
| skip/null/result, refresh, expiry, cleanup | 2, 3 |
| atomicity/linearizability | 3, 4 |
| provider-injectable fixture | 1, 2 |
| custom backend 적용 | 2, 3 |
| core 문서와 책임 경계 | 5 |
| test/detekt/ABI | 6 |

## 인라인 6관점 계획 리뷰

| 관점 | 판정 | 근거와 처분 |
|---|---|---|
| 성능 | P0=0, P1=0 | bounded 100회 경합과 단일 key polling 명령을 Task 3/4에 배치 |
| 안정성 | P0=0, P1=0 | RED 증거, hang timeout, lifecycle cleanup, 반복 검증 포함 |
| 보안 | P0=0, P1=0 | credential과 external provisioning을 범위 제외하고 provider 책임 명시 |
| 운영 | P0=0, P1=0 | rollout 없음, 미보증 범위와 rollback 지점 문서화 |
| 개발자/API | P0=0, P1=0 | testFixtures compile부터 시작하고 public surface 최소화 검토 포함 |
| 사용자/caller | P0=0, P1=0 | locale README, 적용 예제, unsupported strategic async 구분 포함 |

통합 점검에서 모든 spec 수용 기준이 선행 Task와 command에 연결됐다. module/workflow,
Spring, Exposed, streaming, dependency hazard는 변경 파일과 무관해 N/A다. coroutine
cancellation은 production coroutine code를 바꾸지 않으며 fixture의 bounded completion과
dispatcher 경계를 검토한다. 최종 판정은 `P0=0`, `P1=0`이다.

## Writer DoD

- SPW-01 PASS: 실행자는 repository context가 없는 개발자이며 exact file/command를 적었다.
- SPW-02 PASS: dependency order, TDD, validation, docs, review, rollback, PR gate를 포함했다.
- SPW-03 PASS: Korean naturalness KO-01~KO-06을 확인하고 code token을 보존했다.
- SPW-04 PASS: spec의 일곱 수용 기준을 Task 표로 추적했다.
- SPW-05 PASS: placeholder 없이 checkbox, code fence, expected result를 최종 read-back했다.

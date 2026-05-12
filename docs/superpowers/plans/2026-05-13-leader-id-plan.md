# Implementation Plan — `leaderId` audit identity for `LeaderElector` / `LeaderGroupElector` API

- **Issue**: #72 — feat: `@LeaderGroupElection` leaderId 지원 (LeaderGroupElector API 변경)
- **Spec**: [`docs/superpowers/specs/2026-05-12-leader-id-design.md`](../specs/2026-05-12-leader-id-design.md) (**CONVERGED** 2026-05-13, 17 rounds Step 2-R)
- **Plan date**: 2026-05-13
- **Branch**: `feat/leader-group-leaderid-72` (off `origin/develop`)
- **Worktree**: `.worktrees/feat-leader-group-leaderid`
- **Plan author**: Codex (feature-dev:code-architect, Opus) — Step 3 implementation contract

> **이 문서의 위치**: spec 은 *무엇을* 어떻게 *설계*하느냐를 정의한다. 본 plan 은 그 spec 을 8 개 PR / ~85 task 로 *어떤 순서로* / *어디서* / *어떤 acceptance gate* 로 *구현*하는지의 단일 계약서다. spec § 인용은 `spec §N` 형태로 짧게 reference 하며, 본 plan 은 **task graph / depends-on / DoD** 에 집중한다.
> 모든 코드 snippet 은 **English**, prose 는 한국어 (internal contributor doc). KDoc 은 spec acceptance criteria 와 동일하게 **English 의무**.

---

## 0. Plan 사용 규약

### 0.1 Task entry 표기 (모든 task 동일)

```
T<NN> [complexity] — <one-line title>
  PR: PR<n>
  Files:
    - <abs-or-module path>
  Depends-on: T<m>, T<k>  (또는 `-` for PR1 leaf)
  Description: 1-2 줄.
  Acceptance:
    - bullet 1
    - bullet 2
```

`complexity` 라벨 의미:

| 라벨 | 의미 | 예상 effort |
|------|------|-------------|
| `high` | multi-file / cross-module / 신규 invariant / silent-failure risk 보유 | 0.5–2일 |
| `medium` | 단일 모듈 / clear pattern / 기존 코드 mutation | 2–4시간 |
| `low` | 신규 단일 파일 / annotation / 상수 / docs | < 2시간 |

### 0.2 PR section header

```
## PR<n> — Phase <X>[+<Y>] — <title>
  depends-on: <prev PRs>
  scope: <한 줄 요약>
  freeze: <yes/no — testFixture 영향 여부>
```

### 0.3 Mandatory build / test command per task

모든 GREEN-step 직후 다음 command 가 clean 통과해야 함 (없으면 task = incomplete):

```bash
./gradlew :<module>:build           # 본 모듈 build (compile + test + detekt + kover)
./gradlew :<module>:test            # only test
./gradlew build -x test --no-daemon # cross-module compile sanity (root)
./gradlew detekt                    # detekt clean across touched modules
```

`./gradlew :leader-core:build` 는 PR1 의 모든 task GREEN gate, 다른 PR 은 각자 module + leader-core (downstream rebuild).

### 0.4 bluetape4k-patterns 의무 (모든 신규 코드)

- `CancellationException` 항상 명시 catch + rethrow ← `catch (Exception)` 앞에 위치
- `runCatching {}` 블록 안 suspend 호출 금지 → manual try-catch
- `KLogging` companion 사용 — hot path `nextLeaderId()` 에는 log 금지
- `requireNotBlank("paramName")` / `requireGt(0, "paramName")` 등 fail-fast — `IllegalArgumentException`
- `assertXxx()` 사용 금지 (deprecated, `AssertionError`)
- `@Synchronized` / `synchronized {}` 금지 (Virtual Thread parity) → `reentrantLock()`
- `!!` 금지 → `?.` / `?:` / `requireNotNull()`
- `atomicfu` 는 class property 만 (메서드 local 금지)
- 동종 String × 2 / Int × 2 파라미터 → data class wrapping (본 spec 의 `LeaderSlot` 이 직접 예시)
- `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` + JUnit5 + MockK + bluetape4k-assertions
- 예외 검증: `assertFailsWith<T> { }` / `coInvoking { ... } shouldThrow T::class` (suspend) — `invoking shouldThrow` / `assertThrows` / `kotlin.test.assertFailsWith` 금지
- Concurrency tester: `MultithreadingTester` / `SuspendedJobTester` / `StructuredTaskScopeTester` 의무 — 직접 `Thread` / `Executors` / `coroutineScope { launch }` 금지

### 0.5 PR-merge gate (모든 PR 공통)

1. 본 모듈 `./gradlew :<module>:build` clean
2. `ide_diagnostics` 오류 0, 미해결 `@Deprecated` 0 (단 spec 정의 deprecated alias 는 의도된 1건)
3. Kover threshold: `leader-core` ≥ 80%, backend module ≥ 60%
4. `oh-my-claudecode:code-reviewer` 실행 → HIGH/CRITICAL 모두 해소
5. PR body: 실행한 test command + 통과수 + 소요시간 + 수정 이유
6. README locale set 동기화 (`README.md` + `README.ko.md`)
7. 변경된 public symbol 모두 English KDoc 추가/수정
8. worktree (`.worktrees/feat-leader-group-leaderid/`) 에서 작업
9. CHANGELOG.md `Unreleased` 섹션 업데이트 (PR 별로 누적)

---

## 0.6 PR Graph (depends-on)

```
PR1 (Phase A + B + C + testFixture contract)
  │     (PR1 자체에 freeze doc 포함 — Step 3-R B1: PR1.5 develop push 제거)
  │     │
  │     ├── PR2 (Phase D — Lettuce + Redisson)
  │     ├── PR3 (Phase E — Mongo)
  │     ├── PR4 (Phase F1 — Hazelcast)
  │     ├── PR5 (Phase F2 — ZooKeeper)
  │     └── PR6 (Phase G — Exposed JDBC + R2DBC)
  │            │
  │            └── PR7 (Phase H + I — AOP + Spring autoconfig)
  │                   │
  │                   └── PR8 (Phase J + K — cross-backend tests + docs/CHANGELOG)
```

**Critical path 길이**: PR1 → PR2-6 (병렬) → PR7 → PR8 = 4 sequential merges + 5 parallel backend PRs (PR1.5 develop push 제거 per B1).

**Freeze reference 방식** (Step 3-R B1 fix — workspace `CLAUDE.md` branch protection: develop 직접 push 금지):
- PR1.5 sub-commit 메커니즘 **제거**. PR1 자체에 freeze doc 포함 (placeholder hash 없이 **GitHub PR 번호 reference** 사용).
- `2026-05-13-leader-id-testfixture-freeze.md` 에 `Frozen at: PR #<TBD> merge to develop` 로 작성 (commit SHA 가 아닌 PR 번호 reference)
- PR2-6 worker 는 PR1 merge 후 `origin/develop` HEAD 에서 worktree 분기 — `git fetch && git checkout origin/develop` 만 필요. 명시 freeze commit hash 의존 없음.
- testFixture API 변경이 발생하면 별도 follow-up PR 로 처리; freeze contract 의 의도(시그니처 안정)는 PR2-6 worker 가 PR1 merge 직후 분기하는 것으로 자연스럽게 보존

---

## 0.7 Round 17 follow-up tasks summary

Round 17 사후 발견 사항은 다음 5개 task 로 plan 에 포함 (모두 PR 분배 명시):

| Task ID | Complexity | PR | 설명 |
|---------|------------|----|------|
| T81 | medium | **deferred (post-PR8)** | AUTO source LRU throttle redesign — `LeaderSlot.source` field 추가, source-aware throttle key. PR8 cross-backend test 후 별도 follow-up issue 등록 (architect N16-2 / codex N17-3) |
| T82 | medium | PR1 | `super.X` enforcement contract test in `AbstractLeaderIdContractTest` — bridge WARN counter == 0 after backend override (Round 17 N17-1) |
| T83 | low | PR1 | detekt custom rule — `super.runIfLeader(slot, ...)` / `super.runIfLeaderResult(slot, ...)` / `super.runAsyncIfLeader(slot, ...)` 호출 forbid (Round 17 N17-1 detekt counterpart, T68c 확장) |
| T84 | low | PR7 | `LeaderRecorderContextDropLog` MeterRegistry-absent fallback log parity — `LeaderElectorBridgeLog` 와 동일 INFO log 의무 (Round 17 N17-4) |
| T85 | low | PR8 | bridge double-count Prometheus query rubric in T70 KDoc — `rate()` 금지, `idelta()` + setGlobal swap timestamp correlation 명시 (Round 17 N17-2 / N16-1) |

> T81 의 `deferred` 사유: spec §17 "잔존 outstanding (Step 3 plan task 로 흡수)" 가 "AUTO source LRU throttle re-design — follow-up issue 로 deferred" 로 명시. 본 plan 은 task 로 포함하되 `PR: deferred` 라벨 — PR8 merge 후 follow-up issue 로 별도 진행, PR1-PR8 의 LeaderSlot API freeze 를 churn 시키지 않음.

---

## 1. PR1 — Phase A + B + C — Core types + interfaces + Local backend + testFixtures contract

- **depends-on**: nothing
- **scope**: `leader-core` 모듈 단독 변경 + testFixtures contract abstract class. 모든 backend interface 가 `LeaderSlot` overload 의 bridge default 메서드를 가지며 기존 `lockName` overload 로 delegate → cross-module compile 깨짐 없이 점진 migration 가능 (spec §D6 Round 5 codex B1)
- **freeze**: **YES** — 본 PR merge 시 `testFixture` API + `LeaderLockHandle.Real` constructor + `LeaderLockHandle.real(...)` factory signature 가 frozen. 변경 시 PR2-6 모두 rebase 필요

### 1.0 Phase A0 — grep gate (PR1 start)

PR1 첫 commit 전에 다음 명령을 worktree 에서 실행하고 결과를 PR description 에 첨부. host:pid 기대 reader 가 있으면 `lease.nodeId` 로 patch 후 진행 (spec §D3 grep gate 확장):

```bash
# 1. worktree 내 lease.leaderId consumer
grep -rn "lease.leaderId" /Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-leader-group-leaderid

# 2. downstream consumers (host:pid 기대 reader 가 있는지)
grep -rn "lease.leaderId\|LeaderLease.*leaderId" \
  /Users/debop/work/bluetape4k/{bluetape4k-workshop,ocean-workshop,clinic-appointment} 2>/dev/null || true

# 3. 직렬화 consumer
grep -rEn "@JsonCreator.*LeaderLease|Kryo.*LeaderLease" \
  /Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-leader-group-leaderid

# 4. positional constructor caller (5번째 positional 깨질 위험)
grep -rn "LeaderLease(" --include="*.kt" --include="*.java" \
  /Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-leader-group-leaderid

# 5. named-arg constructor caller
grep -rEn "LeaderLease\(.*leaderId\s*=" \
  /Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-leader-group-leaderid

# 6. fencing-token 비교 패턴 (handle.token 마이그레이션 대상)
grep -rEn "\.leaderId\s*==|\.leaderId\.equals\(" \
  /Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-leader-group-leaderid

# 7. LeaderLockHandle.real(...) factory call site — positional-full 확인
grep -rEn "LeaderLockHandle\.real\(" --include="*.kt" \
  /Users/debop/work/bluetape4k/bluetape4k-leader/.worktrees/feat-leader-group-leaderid/leader-*/src
```

> 7개 grep 모두 결과를 PR description 표로 첨부, 영향 row 별로 "patch / N/A / migrate to handle.token" decision 명시.

### Phase A — Core types

#### T1 [low] — `LeaderSlot` value object
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderSlot.kt` (NEW)
- **Depends-on**: —
- **Description**: spec §D1 의 `data class LeaderSlot(val lockName: String, val leaderId: String) : Serializable` 신규. init `requireNotBlank` × 2; companion `serialVersionUID = 1L`; `of(lockName, provider)` factory.
- **Acceptance**:
  - `requireNotBlank` 위반 시 `IllegalArgumentException`
  - `Serializable` (`leader-core/.../LeaderLease.kt` parity)
  - English KDoc + `## Contract` 섹션 + `LeaderSlot.of(...)` 예제

#### T2 [low] — `LeaderIdProvider` fun interface
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/LeaderIdProvider.kt` (NEW)
- **Depends-on**: —
- **Description**: spec §D2 — `fun interface LeaderIdProvider { fun nextLeaderId(lockName: String): String }`. KDoc 에 "MUST NOT throw / MUST NOT block / MUST be thread-safe / MUST return non-blank" 4-rule contract 명시.
- **Acceptance**:
  - `fun interface` → Java/Kotlin lambda 양쪽 호환
  - KDoc 의 4-rule contract block — English

#### T3 [low] — `RandomLeaderIdProvider`
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/RandomLeaderIdProvider.kt` (NEW)
- **Depends-on**: T2
- **Description**: spec §D2 — Base58 default (length=12). `init { length.requireGt(0, "length") }`. `companion { const val DefaultLength: Int = 12; @JvmField val Default: LeaderIdProvider = RandomLeaderIdProvider() }`.
- **Acceptance**:
  - `Base58.randomString(length)` 사용 (`bluetape4k-core` 의존)
  - `Default` 가 `@JvmField` (Java 호환)
  - `RandomLeaderIdProvider(0)` → `IllegalArgumentException`

#### T4 [low] — `HostnamePidLeaderIdProvider` (opt-in, PII warning)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/HostnamePidLeaderIdProvider.kt` (NEW)
- **Depends-on**: T2
- **Description**: spec §D2 — `"${LeaderNodeId.Default}:${Base58.randomString(suffixLength)}"`. KDoc 에 "**PII warning** — do NOT use in multi-tenant SaaS where hostname is sensitive".
- **Acceptance**:
  - `suffixLength.requireGt(0, "suffixLength")`
  - KDoc PII warning block + opt-in usage 예제
  - `LeaderNodeId.Default` 의존 (`leader-core` 내부)

#### T5 [low] — `CompositeLeaderIdProvider` (prefix wrap)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/CompositeLeaderIdProvider.kt` (NEW)
- **Depends-on**: T2, T3
- **Description**: spec §D2 — tenant tag prefix wrapping. `init { prefix.requireNotBlank("prefix") }`. delegate 기본 = `RandomLeaderIdProvider.Default`.
- **Acceptance**:
  - `nextLeaderId(lockName)` = `"$prefix$separator${delegate.nextLeaderId(lockName)}"`
  - KDoc 에 tenant tag 사용 예제 (multi-tenancy follow-up #42 reference)

#### T47 [low] — `LeaderIdSource` enum
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/LeaderIdSource.kt` (NEW)
- **Depends-on**: —
- **Description**: spec §D10 — `enum class LeaderIdSource { LITERAL, SPEL, PROPERTY, AUTO }`. provenance 추적.
- **Acceptance**:
  - 4 values exactly; bounded cardinality for Micrometer tag
  - KDoc 에 source 별 의미 + Micrometer cardinality 영향 명시

#### T48 [medium] — `LeaderIdResolutionException`
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/LeaderIdResolutionException.kt` (NEW)
- **Depends-on**: —
- **Description**: spec §R4/O10 — `class LeaderIdResolutionException(msg: String, cause: Throwable? = null) : RuntimeException(msg, cause)`. **always-RETHROW** 의무 — `failureMode` 정책 무관 (silent-failure blocker). `SkipReason.LEADER_ID_RESOLUTION` enum value 는 미추가 (Round 3 NF1 — always-RETHROW path 에서 도달 불가).
- **Acceptance**:
  - 클래스 KDoc 에 "**MUST be rethrown** regardless of `failureMode` — see spec §D7" 명시
  - 이 예외만 dedicated `catch` 하는 패턴은 PR7 aspect 에서 적용

#### T49 [medium] — `safeNextLeaderId` defensive top-level helper + `@LeaderInternalApi`
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/LeaderIdProviders.kt` (NEW)
- **Depends-on**: T2, T3, T63
- **Description**: spec §D2 R3-B1/R3-M2 — `public fun safeNextLeaderId(provider, lockName): String` (cross-module `leader-spring-boot` aspect access). catch order: `CancellationException` (rethrow) → `InterruptedException` (Thread.interrupt() + rethrow) → blank 결과 (warn + Default fallback) → broad `Exception` (error log + Default fallback). `@LeaderInternalApi` opt-in 표식.
- **Acceptance**:
  - 4 catch branches 순서 정확
  - `KotlinLogging.logger { }` top-level private val (KLogging companion 안 됨 — top-level 함수)
  - `@LeaderInternalApi` 적용 (별도 `@RequiresOptIn` annotation, T63)
  - 단위 테스트: throw provider / blank provider / cancel-throw / interrupt-throw 4 분기 검증

#### T63 [low] — `@LeaderInternalApi` opt-in annotation
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/LeaderInternalApi.kt` (NEW)
- **Depends-on**: —
- **Description**: spec §D2 Round 3 R3-B1 — `@RequiresOptIn(level = RequiresOptIn.Level.WARNING)` annotation. module SPI 와 application API 구분 표식.
- **Acceptance**:
  - `@Retention(BINARY)` + `@Target(FUNCTION, CLASS, PROPERTY)`
  - KDoc 에 "module SPI, not application API" 명시

#### T6 [medium] — `LeaderLease.nodeId` 신규 + semantic 노트 (`auditLeaderId` rename 은 T57)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLease.kt` (MODIFY)
- **Depends-on**: T57
- **Description**: spec §D3 — `val nodeId: String? = null` additive 필드 추가, `nodeId?.requireNotBlank("nodeId")`. semantic 변경 KDoc 보강.
- **Acceptance**:
  - 기존 5-arg positional constructor 호환 (T57 의 rename 이 우선 적용)
  - `nodeId` 가 마지막 positional (5번째)

#### T57 [medium] — `LeaderLease.leaderId → auditLeaderId` rename + deprecated getter + `serialVersionUID 1L → 2L`
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLease.kt` (MODIFY)
- **Depends-on**: A0 grep gate
- **Description**: spec §D3 Round 2 N2 — field `leaderId` → `auditLeaderId` rename, `@Deprecated val leaderId get() = nodeId ?: auditLeaderId` getter 추가, `serialVersionUID 1L → 2L`. KDoc 의 fencing-token semantics regression 노트 명시.
- **Acceptance**:
  - 기존 `LeaderLease.leaderId` 호출 site 모두 deprecation warning (강제 fix 는 다음 major)
  - serialVersionUID 정확히 2L
  - 단위 테스트 — `nodeId != null` 시 `lease.leaderId == lease.nodeId`, `nodeId == null` 시 `lease.leaderId == lease.auditLeaderId` (Round 6 codex #4)

#### T7 [medium] — `LeaderRunResult.Elected.leaderId: String? = null` 추가 + `@JvmOverloads`
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt` (MODIFY)
- **Depends-on**: —
- **Description**: spec §D4 Round 2 F7 — `data class Elected<out T> @JvmOverloads constructor(val value: T?, val leaderId: String? = null) : LeaderRunResult<T>`. nullable (NOT `""` sentinel). `@JvmOverloads` 로 Java byte-code 두 bridge 생성.
- **Acceptance**:
  - 기존 Kotlin caller `Elected(value)` 컴파일 통과
  - Java caller `new LeaderRunResult.Elected<>(value)` 컴파일 통과
  - `Elected(x) == Elected(x)` (둘 다 default null) 통과
  - 단위 테스트 — destructuring `val (v) = elected` (1-arg) 및 `val (v, id) = elected` (2-arg) 둘 다 통과

#### T58 [low] — `@JvmOverloads` verification test
- **PR**: PR1
- **Files**:
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultJvmCompatTest.kt` (NEW)
- **Depends-on**: T7
- **Description**: spec §D4 F7 — reflection 으로 `Elected.<init>` 의 method count 가 ≥2 (1-arg + 2-arg) 확인. Java binary-compat 회귀 방지.
- **Acceptance**:
  - test fail 시 `@JvmOverloads` 누락 즉시 가시화

#### T8 [medium] — `LeaderLockHandle.Real.auditLeaderId` (END positional, nullable) — T56 와 동일 작업
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLockHandle.kt` (MODIFY)
- **Depends-on**: A0 grep gate (#7)
- **Description**: T56 으로 통합. 본 entry 는 spec §17 의 task ID 와 매핑 보존을 위해 stub. **실제 작업은 T56 참조.**
- **Acceptance**:
  - T56 의 acceptance 와 동일

#### T56 [high] — `LeaderLockHandle.Real` constructor `auditLeaderId` END positional + `withReentryDepth` 명시 propagate + factory `real(...)` END positional
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLockHandle.kt` (MODIFY)
- **Depends-on**: A0 grep gate (#7)
- **Description**: spec §D9 Round 2 F2/F3/N5 + Round 3 R3-H1. `Real internal constructor(..., val auditLeaderId: String? = null)` — END positional. `equals/hashCode` 변경 없음 (auditLeaderId 제외, identity 는 token). `withReentryDepth(n)` explicit constructor 호출에 `auditLeaderId` 명시 propagate. Factory `LeaderLockHandle.real(..., auditLeaderId: String? = null)` 도 END positional. **`Real` 은 `class` 유지** (data class 아님).
- **Acceptance**:
  - 기존 backend factory call `LeaderLockHandle.real(identity, token, acquiredAtNanos, slotId, threadId, reentryDepth, delegate)` 컴파일 OK (default null 자동)
  - `equals/hashCode` 는 `(identity, token, reentryDepth, slotId)` 만 — `auditLeaderId` 제외
  - 단위 테스트 — `withReentryDepth(n)` 호출 후 `result.auditLeaderId == original.auditLeaderId`

#### T9 [low] — `LeaderElectionInfo.leaderId / leaderIdSource` 추가 (nullable, pairing convention)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/LeaderElectionInfo.kt` (MODIFY)
- **Depends-on**: T47
- **Description**: spec §D5 Round 9 NEW-9-3 — `val leaderId: String? = null`, `val leaderIdSource: LeaderIdSource? = null` 두 필드 additive. **PR1 에서는 `init { require(...) }` 미도입** (모든 기존 aspect call site 가 throw 됨) — pairing invariant 는 `validate()` opt-in method (T65) 로 호출자가 검증; init invariant 는 PR7 (T54) 에서 도입.
- **Acceptance**:
  - 기존 `LeaderElectionInfo(lockName, true)` 컴파일 OK (default null)
  - `CoroutineContext.Element` 직접 구현 (worktree 기존 shape 유지)
  - `key` override

#### T65 [medium] — `LeaderElectionInfo.validate()` opt-in method
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/LeaderElectionInfo.kt` (same as T9, MODIFY)
- **Depends-on**: T9
- **Description**: spec §D5 Round 3 NF2/R3-H3 — `fun LeaderElectionInfo.validate(): LeaderElectionInfo = apply { ... }`. pairing invariant 2개 require (leaderId↔leaderIdSource, wasElected↔hasLeader). contract test (T16) 에서 모든 backend 생성 site 의 위반 즉시 검출.
- **Acceptance**:
  - 두 invariant 모두 require 적용
  - 단위 테스트 — 4 pairing 조합 (null/null, val/val, null/val, val/null) → 후자 2개 throw
  - PR1 에서 init require 절대 금지 (Round 2 A1/F5)

#### T54 [medium] — `LeaderElectionInfo` init invariant — **PR7 에서 도입** (placeholder in PR1)
- **PR**: PR7
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/LeaderElectionInfo.kt` (MODIFY)
- **Depends-on**: PR2..PR6 모두, T71
- **Description**: spec §D5 A1/F5 deferred — PR7 에서 aspect call site 가 모두 `(name, true, resolved.value, resolved.source)` 로 마이그레이션 완료 후 `init { require(...) }` 추가. 단위 테스트 (4 pairing 조합).
- **Acceptance**:
  - PR7 시점에 init require 적용 + 모든 backend test green
  - 4 invariant 조합 단위 테스트

---

### Phase B — Core interfaces (PR1, bridge default)

> spec §D6 Round 5 codex B1 — 8 elector interface 모두 `LeaderSlot` overload + Result variant 를 `default` 메서드로 추가. **기본 구현이 기존 `lockName` overload 에 delegate** + `LeaderElectorBridgeLog.global().warnOnBridgeUse / warnOnResultBridgeUse` 호출 (Round 6/9/13/16 silent-failure regression 방어). PR2-6 의 backend 가 점진 override. PR1 시점 backend 코드 변경 없이 컴파일 통과.

#### T68 [high] — 8 elector interface 에 `LeaderSlot` bridge default + Result variant 추가
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderGroupElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderGroupElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: T1, T75
- **Description**: spec §D6 Round 5 B1 + Round 6 C1/H1 + Round 7 H5/H6 + Round 13 P0 + Round 16 N16-1.
  - sync: `fun <T> runIfLeader(slot, action): T?` default → `runIfLeader(slot.lockName, action)` + `warnOnBridgeUse`
  - sync result: `fun <T> runIfLeaderResult(slot, action): LeaderRunResult<T>` default — `elected` flag pattern + nested `runIfLeader(slot, ...)` 호출 + `Elected(v, leaderId=null)` (fabrication 차단)
  - async: `runAsyncIfLeader(slot, executor, action)` + `runAsyncIfLeaderResult(slot, executor, action)` — `AtomicBoolean(false)` 사용
  - VT: `runAsyncIfLeader(slot, action)` + `runAsyncIfLeaderResult(slot, action)` — `AtomicBoolean(false)` + `VirtualFuture<T>` 반환
  - suspend: `suspend fun runIfLeader(slot, action)` + `suspend fun runIfLeaderResultSuspend(slot, action)` — manual try-catch + `CancellationException` rethrow first (NO `runCatching`)
  - 모든 bridge default 가 `LeaderElectorBridgeLog.global().warnOnBridgeUse(this::class, slot)` / `warnOnResultBridgeUse(...)` 호출 — guard 제거 (Round 16 N16-1)
- **Acceptance**:
  - 모든 기존 backend 모듈 `./gradlew build -x test` 컴파일 통과 (코드 변경 0)
  - bridge 사용 시 first-time WARN 1회 (per `(class, leaderId)` LRU pair)
  - bridge result variant 가 `Elected(v, leaderId=null)` 반환 (NOT `slot.leaderId` — fabrication 차단)
  - suspend bridge: `runCatching` 사용 0건 (grep 으로 확인)
  - 단위 테스트 — 각 8 interface 의 bridge default 가 `lockName` overload 호출함을 확인

#### T10 [high] — `LeaderElector` + `LeaderGroupElector` (sync) `LeaderSlot` primary + Result variant
- **PR**: PR1
- **Files**: `LeaderElector.kt`, `LeaderGroupElector.kt` (T68 의 일부)
- **Depends-on**: T68 (이 task 는 spec §17 T10 의 일관성을 위한 mapping; 실제 작업은 T68 에 통합)
- **Description**: T68 의 sync 부분. spec §17 의 task ID 매핑 보존을 위해 entry 유지.
- **Acceptance**: T68 의 sync acceptance 와 동일

#### T11 [high] — `AsyncLeaderElector` + `AsyncLeaderGroupElector` primary + result
- **PR**: PR1
- **Files**: `AsyncLeaderElector.kt`, `AsyncLeaderGroupElector.kt` (T68 의 일부)
- **Depends-on**: T68
- **Description**: T68 의 async 부분.
- **Acceptance**: T68 의 async acceptance 와 동일

#### T12 [high] — `VirtualThreadLeader{,Group}Elector` primary + result
- **PR**: PR1
- **Files**: `VirtualThreadLeaderElector.kt`, `VirtualThreadLeaderGroupElector.kt` (T68 의 일부)
- **Depends-on**: T68
- **Description**: T68 의 VT 부분. `AtomicBoolean(false)` 사용 (memory visibility).
- **Acceptance**: T68 의 VT acceptance 와 동일

#### T13 [high] — `SuspendLeader{,Group}Elector` primary + result
- **PR**: PR1
- **Files**: `coroutines/SuspendLeaderElector.kt`, `coroutines/SuspendLeaderGroupElector.kt` (T68 의 일부)
- **Depends-on**: T68
- **Description**: T68 의 suspend 부분. manual try-catch + CE rethrow first, NO `runCatching`.
- **Acceptance**: T68 의 suspend acceptance 와 동일

#### T75 [medium] — `LeaderElectorBridgeLog` (LRU throttle + global holder + 2 counter)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/identity/LeaderElectorBridgeLog.kt` (NEW)
- **Depends-on**: T1, T2
- **Description**: spec §D6 H1/H4 + Round 8 codex #3 + Round 9 N9-1~N9-5 + Round 10 N10-2/N10-3 + Round 11 N11-4 + Round 14 NEW-14-5 + Round 16 N16-1.
  - `cacheSize.requireGt(0, "cacheSize")` (Round 9 N9-5)
  - `warnedPairs: MutableMap<String, Boolean>` LinkedHashMap accessOrder=true (LRU)
  - `warnedResultPairs` 별도 map (Round 11 N11-4 — slot vs result 상호 eviction 회피)
  - `warnOnBridgeUse(implClass, slot)` — `${implClass.qualifiedName}|slot|${slot.leaderId}` key, `[OMC-BRIDGE-SLOT-DROP]` log token
  - `warnOnResultBridgeUse(implClass, slot)` — `[OMC-BRIDGE-RESULT-DROP]` log token + backend MUST override BOTH 메시지
  - `droppedCounter` + `droppedResultCounter` (`AtomicLong`)
  - `droppedAuditCount()` / `droppedResultBridgeCount()` accessor
  - companion `globalInstance: @Volatile var` + `setGlobal(instance)` + `global()`. setGlobal swap 시 prev counter log.info
- **Acceptance**:
  - `LeaderElectorBridgeLog(0)` → `IllegalArgumentException`
  - `setGlobal()` swap 시 INFO log 출력 (prev.dropped + prev.resultDropped)
  - 단위 테스트 (T82 의 dependency) — 같은 (class, leaderId) 반복 호출 시 WARN 1회만
  - **AUTO source LRU limitation 문서화** (Round 16 N16-2): KDoc 에 "AUTO source 의 unique-per-call leaderId 는 LRU churn → unthrottled WARN flood — see T81 follow-up"
  - **Bounded recent-pair throttle 의미** KDoc 명시

#### T74 [medium] — `LeaderRecorderContextDropLog` (single class, global holder, drop counter)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/LeaderRecorderContextDropLog.kt` (NEW)
- **Depends-on**: T76
- **Description**: spec §D10 Round 7 H3 + Round 9 N9-1/N9-2/N9-3 + Round 10 N10-3 + Round 8 N8-1/T77.
  - `warnedClasses: ConcurrentHashMap.newKeySet<KClass<*>>()`
  - `droppedCounter: AtomicLong`
  - `warnOnDrop(recorderClass, context)` — `context as? LeaderAopMetricsContext.Identified ?: return` (Round 8 N8-1 sealed 호환); first add 시 WARN ("did not override 4-arg context overload; audit context dropped")
  - companion `globalInstance` + `setGlobal(instance)` (Round 10 N10-3 swap log.info) + `global()`
- **Acceptance**:
  - `context == Unknown` 호출 시 early return (no counter increment, no warn)
  - `context == Identified` 호출 시 counter increment + first-time WARN per class
  - 단위 테스트 (concurrent — `MultithreadingTester`) — 100 thread × 100 round 호출 시 WARN 1회, counter == 10000

#### T77 [high] — `LeaderRecorderContextDropLog` sealed 호환 검증 단위 테스트
- **PR**: PR1
- **Files**:
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/metrics/LeaderRecorderContextDropLogSealedTest.kt` (NEW)
- **Depends-on**: T74, T76
- **Description**: spec Round 8 N8-1 / codex #1 — `context as? Identified ?: return` 패턴 회귀 방지. Empty/Unknown 분기 + Identified 분기 모두 테스트.
- **Acceptance**:
  - `Unknown` 으로 호출 → no warn, counter == 0
  - `Identified` 으로 호출 → warn 1회, counter == 1
  - 동일 class 두 번 호출 → counter == 2 (drop), warn == 1 (first-time)

#### T76 [medium] — `LeaderAopMetricsContext` sealed interface (Unknown / Identified)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/LeaderAopMetricsContext.kt` (NEW)
- **Depends-on**: T47
- **Description**: spec §D10 Round 7 N7-5 + Round 8 N8-1/N8-4/N8-5.
  - `sealed interface LeaderAopMetricsContext`
  - `data object Unknown : LeaderAopMetricsContext`
  - `data class Identified(val leaderId: String, val leaderIdSource: LeaderIdSource)` — `init { leaderId.requireNotBlank("leaderId") }` (단일 필드, fanOut 외부 throw 안전)
  - companion `@JvmField val Empty: LeaderAopMetricsContext = Unknown` (Java compat)
- **Acceptance**:
  - `Identified("", LITERAL)` → `IllegalArgumentException`
  - `Identified` equals/hashCode (data class default) — `(leaderId, source)` 기반
  - Java caller `LeaderAopMetricsContext.Empty` 접근 가능

#### T69 [medium] — `LeaderAopMetricsRecorder` 6 메서드 모두 context overload 추가
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/LeaderAopMetricsRecorder.kt` (MODIFY)
- **Depends-on**: T74, T76
- **Description**: spec §D10 Round 5 H2 + Round 7 N7-1. 기존 6 메서드 (`onLockAttempt`/`onLockAcquired`/`onLockNotAcquired`/`onTaskStarted`/`onTaskFinished`/`onTaskFailed`) 각각에 `context: LeaderAopMetricsContext` 파라미터 추가한 backward-compat default overload. 모든 default 구현은 `LeaderRecorderContextDropLog.global().warnOnDrop(this::class, context)` 호출 후 기존 3-arg path 위임.
- **Acceptance**:
  - 6 메서드 × 2 (legacy + context) = 12 default 메서드
  - 기존 사용자 custom recorder 컴파일 OK (override 없어도 OK)
  - 단위 테스트 — context overload 호출 시 default 가 drop log 호출 + legacy delegate

---

### Phase C — Local backend (PR1)

#### T14 [high] — `LocalLeaderStateRegistry` — leaderId / nodeId signature 분리
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderStateRegistry.kt` (MODIFY)
- **Depends-on**: T1, T6, T57, T56
- **Description**: spec §8 backend matrix — `acquireGroup(lockName, leaderId, nodeId, ...)` signature. 이전 `leaderId` 단일 파라미터를 `auditLeaderId` + `nodeId` 두 개로 분리.
- **Acceptance**:
  - `acquireGroup` / `acquire` 시그니처 변경 후 ide_diagnostics clean
  - 단위 테스트 — `auditLeaderId` ≠ `nodeId` 시 lease 가 둘 다 보존

#### T15 [high] — Local backend 6 elector 모두 propagate
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/AbstractLocalLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/AbstractLocalLeaderGroupElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalLeaderGroupElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalAsyncLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalAsyncLeaderGroupElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalVirtualThreadLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/local/LocalVirtualThreadLeaderGroupElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/LocalSuspendLeaderElector.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/LocalSuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: T14, T68
- **Description**: spec §8 uniform pattern — (1) `idProvider: LeaderIdProvider = RandomLeaderIdProvider.Default` ctor 주입, (2) `runIfLeader(slot, action)` primary override + `runIfLeaderResult(slot, action)` override, (3) `runIfLeader(lockName, action)` legacy → `runIfLeader(LeaderSlot(lockName, safeNextLeaderId(idProvider, lockName)), action)` delegate, (4) `slot.leaderId` 를 `LeaderLease.auditLeaderId` + `LeaderLockHandle.Real.auditLeaderId` stamp.
- **Acceptance**:
  - 모든 10 Local elector 가 BOTH `runIfLeader(slot)` AND `runIfLeaderResult(slot)` override (T72 contract test 검증)
  - `LeaderLockHandle.real(..., auditLeaderId = slot.leaderId)` END positional 호출
  - `slot.leaderId == lease.auditLeaderId == handle.auditLeaderId` invariant 통과 (T16 contract test)

#### T16 [high] — `AbstractLeaderIdContractTest` (testFixture) — 4 execution × 2 (single/group) = 최대 8 abstract
- **PR**: PR1
- **Files**:
  - `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractLeaderIdContractTest.kt` (NEW — sync, group + single 통합)
  - `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractSuspendLeaderIdContractTest.kt` (NEW)
  - `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractAsyncLeaderIdContractTest.kt` (NEW)
  - `leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractVirtualThreadLeaderIdContractTest.kt` (NEW)
- **Depends-on**: T68, T75, T76, T74, T65 (Step 3-R codex #1 fix — T82 cycle 제거; T82 는 T16 본문 내부에 통합 test case 로 포함)
- **Description**: spec §10 + Round 5 codex M5 + Round 6 codex #4 + Round 7 N7-4 + Round 17 N17-1.
  - `abstract fun createGroupElector(maxLeaders: Int)`, `abstract fun createSingleElector()`
  - 핵심 테스트:
    - `LeaderRunResult Elected carries leaderId from LeaderSlot AFTER backend migration`
    - `default provider yields unique leaderId per call` (MultithreadingTester)
    - `LeaderLease auditLeaderId equals LeaderSlot leaderId after acquire`
    - `LeaderLease nodeId equals options nodeId after acquire`
    - `with nodeId set, deprecated leaderId getter returns nodeId not auditLeaderId`
    - `with nodeId null, deprecated leaderId getter returns auditLeaderId`
    - `nodeId is preserved on LeaderLease alongside leaderId`
    - `concurrent runIfLeader calls each have distinct leaderId`
    - (T82) `bridge WARN counter is zero when backend overrides slot variants`
    - (T51) `reentrant inner frame preserves auditLeaderId`
    - (T65) `LeaderElectionInfo.validate() passes for backend-produced info`
- **Acceptance**:
  - 4 abstract classes (sync / suspend / async / VT) 각각 group + single 조합 지원 (subclass 가 어느 한쪽 선택)
  - 모든 테스트 `@TestInstance(PER_CLASS)` + JUnit5 + bluetape4k-assertions
  - `MultithreadingTester` / `SuspendedJobTester` / `StructuredTaskScopeTester` 사용 — 직접 Thread/Executors 금지
  - `assertFailsWith<T> { }` / `coInvoking { ... } shouldThrow T::class` 패턴 사용

#### T17 [medium] — `leader-core` Local backend contract subclass (GREEN)
- **PR**: PR1
- **Files**:
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/local/LocalLeaderIdContractTest.kt` (NEW)
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/local/LocalSuspendLeaderIdContractTest.kt` (NEW)
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/local/LocalAsyncLeaderIdContractTest.kt` (NEW)
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/local/LocalVirtualThreadLeaderIdContractTest.kt` (NEW)
- **Depends-on**: T15, T16
- **Description**: Local backend 4 execution model 모두 contract subclass — `createGroupElector` / `createSingleElector` impl.
- **Acceptance**:
  - 4 subclass `./gradlew :leader-core:test --tests "*LeaderIdContract*"` clean
  - `bridge WARN counter == 0` (Local 이 모든 slot variant override)

#### T51 [medium] — reentrant audit preservation contract test
- **PR**: PR1
- **Files**:
  - 추가 `@Test` in `AbstractLeaderIdContractTest.kt` (T16 의 일부)
- **Depends-on**: T16, T56
- **Description**: spec §17 T51 (Silent-failure [medium]) — `withReentryDepth(n)` 이후 `auditLeaderId` 가 보존되는지 검증. 동일 lockName 재진입 시 inner frame 의 `auditLeaderId` == outer frame `slot.leaderId`.
- **Acceptance**:
  - 재진입 3 depth 까지 `auditLeaderId` 변화 없음
  - `LeaderLockHandle.Real.withReentryDepth(n)` explicit constructor (T56) 검증

#### T82 [medium] — `super.X` enforcement contract test (Round 17 N17-1)
- **PR**: PR1
- **Files**:
  - 추가 `@Test` 케이스 (T16 contract test 본문에 통합 — 별도 file 아님; Step 3-R codex #1 cycle fix)
- **Depends-on**: T75 (Step 3-R codex #1 fix — T16 dep 제거; T82 는 T16 본문 내부 추가 case 로 deliver)
- **Description**: Round 17 N17-1 — backend override 안에서 `super.runIfLeader(slot, ...)` / `super.runIfLeaderResult(...)` 호출 금지. contract test 는 backend override 후 `LeaderElectorBridgeLog.global().droppedAuditCount()` / `droppedResultBridgeCount()` 가 **0** 임을 검증.
- **Acceptance**:
  - `@BeforeEach` 에서 `LeaderElectorBridgeLog.setGlobal(LeaderElectorBridgeLog())` AND `LeaderRecorderContextDropLog.setGlobal(LeaderRecorderContextDropLog())` reset 둘 다 (Step 3-R test-engineer symmetric reset)
  - test 종료 시 두 counter 모두 0
  - 위반 시 명확한 fail 메시지

#### T83 [low] — detekt custom rule: `super.runIfLeader(slot, ...)` / `super.runIfLeaderResult(slot, ...)` forbid
- **PR**: PR1
- **Files**:
  - `buildSrc/src/main/kotlin/detekt/SuperBridgeCallForbidRule.kt` (NEW — detekt custom rule)
  - `buildSrc/src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider` (MODIFY or NEW)
- **Depends-on**: T68
- **Description**: spec §D6 T68c + Round 17 N17-1. detekt rule 이 다음 호출을 모두 fail:
  - `super.runIfLeader(<slot>, ...)`
  - `super.runIfLeaderResult(<slot>, ...)`
  - `super.runAsyncIfLeader(<slot>, ...)`
  - `super.runAsyncIfLeaderResult(<slot>, ...)`
  - `super.runIfLeaderResultSuspend(<slot>, ...)`
  - (Round 6) circular delegate: `runIfLeader(slot)` override → `runIfLeader(slot.lockName, ...)` 호출 (양방향)
- **Acceptance**:
  - `./gradlew detekt` 가 PR2-6 backend module 모두 clean
  - 일부러 위반 코드를 추가하면 detekt fail (단위 테스트)

#### T50 [high] — Phase A0 확장 grep gate script
- **PR**: PR1
- **Files**:
  - `scripts/leader-id-phase-a0-grep-gate.sh` (NEW)
- **Depends-on**: —
- **Description**: spec §D3 7개 grep 명령을 single script. CI 에서 PR1 author 가 실행, output 을 PR description 에 첨부. (cross-repo grep — downstream `bluetape4k-workshop` / `ocean-workshop` / `clinic-appointment` 도 검사하나 결과는 informational, 본 PR scope 외)
- **Acceptance**:
  - 7개 grep 모두 실행 + zero exit code
  - host:pid 기대 reader 0 (worktree 내), 발견 시 PR description 에 patch 또는 decision log

#### T67 [low] — testFixture API freeze docs (Step 3-R B1 revised — PR1 self-contained, NO sub-commit)
- **PR**: PR1 only (no PR1.5 — branch protection forbids develop direct push)
- **Files**:
  - `docs/superpowers/specs/2026-05-13-leader-id-testfixture-freeze.md` (NEW)
- **Depends-on**: T16, T56
- **Description**: spec §D8 R3-M1 chicken-and-egg.
  - **PR1 본체 commit**: 다음 내용 placeholder:
    - 각 abstract method signature 명시 (`createGroupElector` / `createSingleElector` / contract test method 리스트)
    - `LeaderLockHandle.Real` constructor positional 순서 (`identity, token, acquiredAtNanos, slotId, acquiringThreadId, reentryDepth, extendDelegate, auditLeaderId`)
    - `LeaderLockHandle.real(...)` factory positional 동일
    - **Step 3-R B2 fix — 추가 freeze 대상**:
      - `LeaderElectorBridgeLog` global holder API: `companion fun global()`, `setGlobal(LeaderElectorBridgeLog)`, instance `droppedAuditCount()`, `droppedResultBridgeCount()`, `warnOnBridgeUse(KClass, LeaderSlot)`, `warnOnResultBridgeUse(KClass, LeaderSlot)`
      - `LeaderRecorderContextDropLog` global holder API: `companion fun global()`, `setGlobal(LeaderRecorderContextDropLog)`, `droppedCount()`, `warnOnDrop(KClass, LeaderAopMetricsContext)`
      - `LeaderAopMetricsContext.Identified(leaderId: String, leaderIdSource: LeaderIdSource)` constructor positional
    - **Freeze reference**: `Frozen at: PR #<TBD> merge to develop` (commit SHA 아닌 GitHub PR 번호 reference) — Step 3-R B1 fix
- **Acceptance**:
  - PR1 description 에 PR2-6 worker branching 절차 명시: "PR1 merge 후 `git fetch && git checkout origin/develop`"
  - freeze doc 는 PR1 자체에 포함 (PR1.5 commit 제거)
- **변경 contract**: 향후 PR2-6 진행 중 testFixture 또는 factory 변경 발생 시 coordinator 통보 → 모든 open PR rebase → 별도 follow-up PR 로 freeze doc 업데이트 (workspace branch protection 준수)

#### T66 [low] — Micrometer counter `leader.aop.leader_id.resolution_failed` (placeholder)
- **PR**: PR1
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/LeaderMetricNames.kt` (MODIFY or NEW const 추가)
- **Depends-on**: —
- **Description**: spec §13 Round 3 NF1 — `SkipReason.LEADER_ID_RESOLUTION` 미추가 대신 Micrometer counter name 상수 추가. `const val METRIC_LEADER_ID_RESOLUTION_FAILED = "leader.aop.leader_id.resolution_failed"`. 실제 increment 는 PR7 aspect 에서 발생.
- **Acceptance**:
  - 상수 public + KDoc 으로 "incremented from PR7 aspect on LeaderIdResolutionException" 명시

#### T70-part1 [low] — `MicrometerNames.kt` 상수 추가 (PR1 부분)
- **PR**: PR1
- **Files**:
  - `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt` (MODIFY)
- **Depends-on**: —
- **Description**: spec §17 T70 Round 13 NEW-13-2 + Round 14 NEW-14-3. 본 PR 에서는 **public 상수만** 추가:
  - `const val TAG_LEADER_ID: String = "leader.id"`
  - `const val TAG_LEADER_ID_SOURCE: String = "leader.id.source"`
  - `const val GAUGE_BRIDGE_DROPPED: String = "leader.aop.bridge.dropped"`
  - `const val GAUGE_BRIDGE_RESULT_DROPPED: String = "leader.aop.bridge.result-dropped"`
  - `const val COUNTER_LEADER_ID_RESOLUTION_FAILED: String = "leader.aop.leader_id.resolution_failed"`
- gauge bean registration 은 PR7 의 T70-part2 (`LeaderMicrometerAutoConfiguration` 변경) 에서 수행.
- **Acceptance**:
  - 5 상수 모두 public + English KDoc
  - 다른 PR (Aspect 호출 site 의 import) 가 import 가능

#### T79 [low] — `LeaderElectorBridgeLog` 의 `setGlobal` swap log + global holder docs
- **PR**: PR1
- **Files**: T75 의 일부 (`LeaderElectorBridgeLog.kt`)
- **Depends-on**: T75
- **Description**: spec §17 T79 Round 10 N10-2 reconcile — Round 8 NEW-1 lifecycle-scoped 약속이 SUPERSEDED. interface default 가 DI 못 받음 → 명시적 static holder + `setGlobal` swap 시 `log.info` 의무. KDoc 에 "test 시 `@DirtiesContext` 만으로 reset 안 됨, 명시 setGlobal 호출 필수" 노트 포함.
- **Acceptance**:
  - T75 의 일부로 통합 — 별도 commit 불필요
  - KDoc 명시

### PR1 acceptance criteria + DoD

- [ ] **A0 grep gate** 7개 명령 모두 실행 + PR description 첨부 (T50)
- [ ] `./gradlew :leader-core:build` clean (compile + test + detekt + kover)
- [ ] `./gradlew build -x test --no-daemon` (root) — backend 모듈 미override 상태에서도 컴파일 통과 (bridge default 의 핵심 작동 검증)
- [ ] Kover `leader-core` ≥ 80%
- [ ] detekt clean (`T83` custom rule 포함)
- [ ] `ide_diagnostics` 오류 0, 미해결 `@Deprecated` 0 (단 `LeaderLease.leaderId` deprecated getter 1건은 의도)
- [ ] 모든 신규 public symbol English KDoc
- [ ] `README.md` + `README.ko.md` 의 `leader-core/` 모듈 → "Audit Identity (preview)" 섹션 추가 (PR8 에서 완전 작성, 본 PR 은 preview pointer)
- [ ] CHANGELOG `## [Unreleased]` 섹션 — `leader-core` 신규 타입 + interface default 추가 + `LeaderLease.leaderId → auditLeaderId` rename + `serialVersionUID 1L → 2L` headline
- [ ] testFixture freeze docs (T67) PR1 placeholder commit 포함
- [ ] PR description: 모든 test command (`./gradlew :leader-core:test`) + 통과수 + 소요시간 + A0 grep 결과 표
- [ ] PR description 에 PR2-6 worker branching 절차 명시 (Step 3-R B1 — PR1.5 제거)

### PR1 test list (file 단위 명시)

| File | 검증 항목 |
|------|-----------|
| `LeaderSlotTest.kt` | `requireNotBlank` × 2, `of()` factory |
| `RandomLeaderIdProviderTest.kt` | length validation, default unique per call (`MultithreadingTester`) |
| `HostnamePidLeaderIdProviderTest.kt` | suffix length validation, hostname:pid format |
| `CompositeLeaderIdProviderTest.kt` | prefix not blank, composition format |
| `LeaderIdSourceTest.kt` | enum values count == 4 |
| `LeaderIdResolutionExceptionTest.kt` | exception construction |
| `LeaderIdProvidersSafeNextTest.kt` | 4 분기 (throw / blank / CE / interrupt) |
| `LeaderLeaseTest.kt` (확장) | `auditLeaderId` rename, `nodeId`, deprecated `leaderId` getter, serialVersionUID 2L |
| `LeaderRunResultJvmCompatTest.kt` | T58 — `@JvmOverloads` reflection |
| `LeaderLockHandleTest.kt` (확장) | T56 — END positional `auditLeaderId`, `withReentryDepth` propagate, equals 제외 |
| `LeaderElectionInfoTest.kt` (확장) | T9 — additive fields, `validate()` 4 pairing |
| `LeaderElectorBridgeLogTest.kt` | T75 — LRU throttle, setGlobal swap log, 2 counter |
| `LeaderRecorderContextDropLogSealedTest.kt` | T77 — sealed 호환 |
| `LeaderAopMetricsContextTest.kt` | T76 — sealed + `requireNotBlank` |
| `LeaderAopMetricsRecorderTest.kt` (확장) | T69 — 6 context overload default |
| `LocalLeaderStateRegistryTest.kt` (확장) | T14 — signature 분리 |
| `Local{Leader,LeaderGroup}ElectorTest.kt` (확장) | T15 — slot variants override |
| `LocalLeaderIdContractTest.kt` + 3 변형 | T17 — 4 execution model contract subclass |
| Bridge interface mock test | T68 — bridge default 가 lockName overload delegate 검증 |

### PR1 README / CHANGELOG impact

- `leader-core/README.md` + `README.ko.md`: "Audit Identity (preview)" 섹션 — full doc 은 PR8 의 T41 에서 작성, 본 PR 은 `LeaderSlot`, `LeaderIdProvider` 의 1-line 소개만
- `CHANGELOG.md`:
  - `### Added` — `LeaderSlot`, `LeaderIdProvider` + 3 impl, `LeaderIdSource`, `LeaderIdResolutionException`, `safeNextLeaderId`, `LeaderElectorBridgeLog`, `LeaderRecorderContextDropLog`, `LeaderAopMetricsContext (sealed)`, `LeaderLease.nodeId`, `LeaderLockHandle.Real.auditLeaderId`, `LeaderElectionInfo.leaderId/leaderIdSource`, 8 interface 의 `LeaderSlot` bridge default 메서드
  - `### Changed` — `LeaderLease.leaderId` → `auditLeaderId` rename, `serialVersionUID 1L → 2L`, `LeaderRunResult.Elected(value, leaderId)` `@JvmOverloads`
  - `### Deprecated` — `LeaderLease.leaderId` getter (one release cycle)
  - `### Breaking` — semantic shift `LeaderLease.leaderId` 의 fencing-token 사용자 → `LeaderLockHandle.token` 마이그레이션 의무

---

## PR1.5 — **REMOVED** (Step 3-R B1)

이전: develop 직접 push sub-commit. workspace `CLAUDE.md` branch protection 위반.
대체: PR1 자체에 freeze doc 포함 (T67 — `Frozen at: PR #<TBD> merge to develop` GitHub PR 번호 reference 사용). PR2-6 worker 는 PR1 merge 후 `origin/develop` HEAD 에서 worktree 분기. commit SHA 의존 없음.

---

## 2. PR2 — Phase D — Lettuce + Redisson backends

- **depends-on**: PR1 (`origin/develop` HEAD after PR1 merge — Step 3-R B1)
- **scope**: 두 Redis backend 가 `LeaderSlot` overload + `slot.leaderId` audit 통합. Lettuce 는 Lua HSET meta, Redisson 은 `RMap<permitId, leaderId>` 병행
- **freeze**: NO

#### T18 [high] — `LettuceSlotTokenGroup.kt` Lua HSET meta hash 추가
- **PR**: PR2
- **Files**:
  - `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/redis/lettuce/semaphore/LettuceSlotTokenGroup.kt` (MODIFY)
- **Depends-on**: PR1.5 hash
- **Description**: spec §8 — ZSET 옆에 `lg:<name>:meta` Hash 로 `token → leaderId` mapping 저장. acquire Lua atomically `ZADD ... + HSET ... auditLeaderId`. release Lua atomically `ZREM + HDEL`. `acquire(lockName, waitTime, leaseTime, audit=leaderId)` signature 확장.
- **Acceptance**:
  - acquire 후 `HGET lg:<name>:meta <token>` == `audit`
  - release 후 `HDEL` 검증
  - Lua atomic — token + meta 둘 다 commit 또는 둘 다 rollback (Testcontainers Redis 검증)

#### T19 [high] — `LettuceLeader{,Group,Suspend*}Elector.kt` propagate
- **PR**: PR2
- **Files**:
  - `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/redis/lettuce/LettuceLeaderElector.kt` (MODIFY)
  - `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/redis/lettuce/LettuceLeaderGroupElector.kt` (MODIFY)
  - `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/redis/lettuce/coroutines/LettuceSuspendLeaderElector.kt` (MODIFY)
  - `leader-redis-lettuce/src/main/kotlin/io/bluetape4k/leader/redis/lettuce/coroutines/LettuceSuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: T18
- **Description**: spec §8 — uniform pattern. `idProvider` ctor 주입 + BOTH `runIfLeader(slot)` AND `runIfLeaderResult(slot)` override + legacy `lockName` overload 를 `LeaderSlot(lockName, safeNextLeaderId(idProvider, lockName))` 으로 delegate.
- **Acceptance**:
  - T72 contract test 통과 — bridge WARN counter == 0, `Elected.leaderId == slot.leaderId`
  - `LeaderLease.auditLeaderId` + `LeaderLockHandle.Real.auditLeaderId` 둘 다 stamp
  - 단위 + Testcontainers Redis 통합 테스트 모두 clean

#### T20 [high] — `RedissonLeader{,Group,Suspend*}Elector.kt` propagate + `RMap<permitId, leaderId>` 동행
- **PR**: PR2
- **Files**:
  - `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redis/redisson/RedissonLeaderElector.kt` (MODIFY)
  - `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redis/redisson/RedissonLeaderGroupElector.kt` (MODIFY)
  - `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redis/redisson/coroutines/RedissonSuspendLeaderElector.kt` (MODIFY)
  - `leader-redis-redisson/src/main/kotlin/io/bluetape4k/leader/redis/redisson/coroutines/RedissonSuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: PR1.5 hash
- **Description**: spec §8 — Redisson `RPermitExpirableSemaphore` 그대로 유지 + `RMap<String, String> "lg:<name>:audit"` 동행 (permitId → leaderId). acquire 시 `RMap.put(permitId, leaderId)`, release 시 `RMap.remove(permitId)`. TTL: Redisson lease 와 함께 expire (Redis TTL 동행).
- **Acceptance**:
  - T72 contract test 통과
  - `RPermitExpirableSemaphore` permitId 와 audit `leaderId` 명확 분리 (R3 Approach 1)
  - Redisson 만료 시 `RMap` 도 정리 (별도 reaper 불필요 — Redis TTL)

#### T21 [medium] — `leader-redis-{lettuce,redisson}` contract subclass
- **PR**: PR2
- **Files**:
  - `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/redis/lettuce/LettuceLeaderIdContractTest.kt` (NEW)
  - `leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/redis/lettuce/LettuceSuspendLeaderIdContractTest.kt` (NEW)
  - `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redis/redisson/RedissonLeaderIdContractTest.kt` (NEW)
  - `leader-redis-redisson/src/test/kotlin/io/bluetape4k/leader/redis/redisson/RedissonSuspendLeaderIdContractTest.kt` (NEW)
- **Depends-on**: T19, T20
- **Description**: PR1 의 `AbstractLeaderIdContractTest` 4종 subclass. Testcontainers `RedisServer.Launcher.redis` singleton.
- **Acceptance**:
  - 4 test class clean — `./gradlew :leader-redis-lettuce:test :leader-redis-redisson:test`
  - bridge WARN counter == 0

#### T72-part-redis [high] — Redis backend의 BOTH override 의무 검증
- **PR**: PR2
- **Files**: T21 contract subclass
- **Depends-on**: T19, T20, T21
- **Description**: spec §17 T72 Round 7 codex #2 + Round 17 N17-1. 모든 Redis backend 가 BOTH `runIfLeader(slot)` AND `runIfLeaderResult(slot)` override. assertion: `assertEquals(slot.leaderId, result.leaderId)` + `bridgeLog.droppedAuditCount() == 0`.
- **Acceptance**:
  - T72 sub-task — Lettuce + Redisson 모두 통과

### PR2 acceptance + DoD

- [ ] `./gradlew :leader-redis-lettuce:build :leader-redis-redisson:build` clean
- [ ] Testcontainers Redis 통합 테스트 clean
- [ ] Lua atomic — token + meta concurrent 검증 (`MultithreadingTester` 100×100)
- [ ] T82 bridge WARN counter == 0
- [ ] README locale set: `leader-redis-lettuce/README.{md,ko.md}` + `leader-redis-redisson/README.{md,ko.md}` "Audit identity 저장 방식" 섹션 (Lettuce: `lg:<name>:meta` Hash, Redisson: `RMap`)
- [ ] CHANGELOG 누적: Lettuce + Redisson backend 가 audit 통과 항목 추가

### PR2 test list

| File | 검증 |
|------|------|
| `LettuceSlotTokenGroupTest.kt` (확장) | Lua HSET/HDEL atomic, audit 동행 |
| `LettuceLeaderIdContractTest.kt` | sync single + group |
| `LettuceSuspendLeaderIdContractTest.kt` | suspend |
| `RedissonLeaderIdContractTest.kt` | sync single + group |
| `RedissonSuspendLeaderIdContractTest.kt` | suspend |
| `RedissonRMapAuditTest.kt` (NEW) | `RMap` lifecycle — acquire/release/TTL 만료 |

---

## 3. PR3 — Phase E — Mongo backend

- **depends-on**: PR1.5 hash
- **scope**: `MongoLock` slot doc 에 `leaderId: String?` 필드 추가, 4 elector propagate
- **freeze**: NO

#### T22 [high] — `MongoLock.kt` slot doc + `leaderId` field
- **PR**: PR3
- **Files**:
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/lock/MongoLock.kt` (MODIFY)
- **Depends-on**: PR1.5 hash
- **Description**: spec §8 — slot doc schema 에 `leaderId: String?` field additive. TTL index 영향 없음 (metadata-only). `findOneAndUpdate` projection 에 `leaderId` 포함.
- **Acceptance**:
  - 기존 row (no `leaderId`) 정상 read (nullable)
  - 신규 row 작성 시 `leaderId` projection 통과
  - Testcontainers Mongo 마이그레이션 테스트 — old → new schema 호환

#### T23 [high] — `MongoLeader{,Group,Suspend*}Elector.kt` propagate
- **PR**: PR3
- **Files**:
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElector.kt` (MODIFY)
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderGroupElector.kt` (MODIFY)
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/coroutines/MongoSuspendLeaderElector.kt` (MODIFY)
  - `leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/coroutines/MongoSuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: T22
- **Description**: spec §8 uniform pattern. BOTH `runIfLeader(slot)` + `runIfLeaderResult(slot)` override.
- **Acceptance**:
  - T72 contract test 통과
  - bridge WARN counter == 0

#### T24 [medium] — `leader-mongodb` contract subclass
- **PR**: PR3
- **Files**:
  - `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderIdContractTest.kt` (NEW)
  - `leader-mongodb/src/test/kotlin/io/bluetape4k/leader/mongodb/MongoSuspendLeaderIdContractTest.kt` (NEW)
- **Depends-on**: T23
- **Description**: PR1 contract abstract 2종 subclass. Testcontainers Mongo singleton.
- **Acceptance**:
  - `./gradlew :leader-mongodb:test` clean

### PR3 acceptance + DoD

- [ ] `./gradlew :leader-mongodb:build` clean
- [ ] Testcontainers Mongo 통합 테스트 clean
- [ ] README locale set: `leader-mongodb/README.{md,ko.md}` "slot doc schema 변경" 섹션
- [ ] CHANGELOG 누적

### PR3 test list

| File | 검증 |
|------|------|
| `MongoLockSchemaTest.kt` (확장) | `leaderId` projection, old row read |
| `MongoLeaderIdContractTest.kt` | sync |
| `MongoSuspendLeaderIdContractTest.kt` | suspend |

---

## 4. PR4 — Phase F1 — Hazelcast backend

- **depends-on**: PR1.5 hash
- **scope**: IMap value 에 `auditLeaderId` 추가, 4 elector propagate
- **freeze**: NO

#### T25 [high] — `Hazelcast*Elector.kt` + IMap value 변경
- **PR**: PR4
- **Files**:
  - `leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderElector.kt` (MODIFY)
  - `leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderGroupElector.kt` (MODIFY)
  - `leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/coroutines/HazelcastSuspendLeaderElector.kt` (MODIFY)
  - `leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/coroutines/HazelcastSuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: PR1.5 hash
- **Description**: spec §8 — IMap value class (예: `HazelcastLeaderEntry`) 에 `auditLeaderId: String?` field. 4 elector 모두 BOTH override.
- **Acceptance**:
  - T72 contract test 통과
  - Hazelcast cluster (Testcontainers) 검증
  - bridge WARN counter == 0

#### T26 [medium] — `leader-hazelcast` contract subclass
- **PR**: PR4
- **Files**:
  - `leader-hazelcast/src/test/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderIdContractTest.kt` (NEW)
  - `leader-hazelcast/src/test/kotlin/io/bluetape4k/leader/hazelcast/HazelcastSuspendLeaderIdContractTest.kt` (NEW)
- **Depends-on**: T25
- **Description**: contract abstract 2종 subclass.
- **Acceptance**:
  - `./gradlew :leader-hazelcast:test` clean

### PR4 acceptance + DoD

- [ ] `./gradlew :leader-hazelcast:build` clean
- [ ] Hazelcast Testcontainers 통합 테스트 clean
- [ ] README locale set: `leader-hazelcast/README.{md,ko.md}` "audit metadata location" 노트
- [ ] CHANGELOG 누적

### PR4 test list

| File | 검증 |
|------|------|
| `HazelcastLeaderEntryTest.kt` | IMap value schema |
| `HazelcastLeaderIdContractTest.kt` | sync |
| `HazelcastSuspendLeaderIdContractTest.kt` | suspend |

---

## 5. PR5 — Phase F2 — ZooKeeper backend

- **depends-on**: PR1.5 hash
- **scope**: znode JSON schema vN+1 (auditLeaderId), 4 elector propagate
- **freeze**: NO

#### T27 [high] — `ZooKeeper*Elector.kt` + znode JSON 변경
- **PR**: PR5
- **Files**:
  - `leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperLeaderElector.kt` (MODIFY)
  - `leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperLeaderGroupElector.kt` (MODIFY)
  - `leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/coroutines/ZooKeeperSuspendLeaderElector.kt` (MODIFY)
  - `leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/coroutines/ZooKeeperSuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: PR1.5 hash
- **Description**: spec §8 — znode payload JSON schema 에 `auditLeaderId` field. 기존 schema (no field) backward-compat (nullable read).
- **Acceptance**:
  - T72 contract test 통과
  - ZK Testcontainers 검증
  - 기존 znode 읽기 시 `auditLeaderId = null` 처리 (schema vN → vN+1 호환)

#### T28 [medium] — `leader-zookeeper` contract subclass
- **PR**: PR5
- **Files**:
  - `leader-zookeeper/src/test/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperLeaderIdContractTest.kt` (NEW)
  - `leader-zookeeper/src/test/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperSuspendLeaderIdContractTest.kt` (NEW)
- **Depends-on**: T27
- **Description**: contract abstract 2종 subclass.
- **Acceptance**:
  - `./gradlew :leader-zookeeper:test` clean

### PR5 acceptance + DoD

- [ ] `./gradlew :leader-zookeeper:build` clean
- [ ] ZK Testcontainers 통합 테스트 clean
- [ ] README locale set: `leader-zookeeper/README.{md,ko.md}` "znode JSON schema vN+1" 노트
- [ ] CHANGELOG 누적

### PR5 test list

| File | 검증 |
|------|------|
| `ZooKeeperLeaderPayloadTest.kt` | JSON schema vN → vN+1 backward-compat |
| `ZooKeeperLeaderIdContractTest.kt` | sync |
| `ZooKeeperSuspendLeaderIdContractTest.kt` | suspend |

---

## 6. PR6 — Phase G — Exposed JDBC + R2DBC backends

- **depends-on**: PR1.5 hash
- **scope**: `leader-exposed-core` 공유 테이블 schema 변경 (`audit_leader_id VARCHAR(256) NULL` — group + single), 2 backend propagate, startup probe, rollback test
- **freeze**: NO

#### T29 [medium] — `LeaderGroupLockTable.kt` + `LeaderLockTable.kt` 두 테이블 모두 `audit_leader_id VARCHAR(256) NULL` 컬럼 추가
- **PR**: PR6
- **Files**:
  - `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockTable.kt` (MODIFY)
  - `leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderGroupLockTable.kt` (MODIFY)
- **Depends-on**: PR1.5 hash
- **Description**: spec §8 + §13 + Round 6 codex #3. `val auditLeaderId = varchar("audit_leader_id", 256).nullable()`. Postgres / MySQL 8.0+ / H2 / SQL Server 모두 호환 (additive, NULL default).
- **Acceptance**:
  - 두 테이블 모두 column 추가
  - `SchemaUtils.createMissingTablesAndColumns()` 자동 ALTER 동작 (`ExposedJdbcSchemaInitializer.kt:55`)

#### T64 [high] — Exposed startup runtime probe (두 테이블 모두 검사)
- **PR**: PR6
- **Files**:
  - `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcSchemaInitializer.kt` (MODIFY)
  - `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcSchemaInitializer.kt` (MODIFY)
- **Depends-on**: T29
- **Description**: spec §13 Round 3 NF3 + Round 5 codex M4 (Phase G T29 확장). startup 시 양쪽 테이블 모두 probe:
  - `SELECT audit_leader_id FROM bluetape4k_leader_locks LIMIT 1`
  - `SELECT audit_leader_id FROM bluetape4k_leader_group_locks LIMIT 1`
  둘 중 어느 하나라도 "column does not exist" 응답 시 actionable error log 후 ALTER 시도. 실패 시 `LeaderInitializationException` throw — friendly error message 포함 (ALTER privilege 안내, manual SQL snippet).
- **Acceptance**:
  - Testcontainers Postgres / MySQL 8.0 / H2 4-way 검증
  - DML-only role 시 startup fail + clear error (Testcontainers role 설정 시뮬레이션)
  - 부분 migration (한쪽만 ALTER) 시 양쪽 모두 ALTER 시도

#### T30 [high] — `Exposed*Elector.kt` JDBC + R2DBC propagate
- **PR**: PR6
- **Files**:
  - `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedLeaderElector.kt` (MODIFY)
  - `leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedLeaderGroupElector.kt` (MODIFY)
  - `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedSuspendLeaderElector.kt` (MODIFY)
  - `leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedSuspendLeaderGroupElector.kt` (MODIFY)
- **Depends-on**: T29, T64
- **Description**: spec §8 uniform pattern. BOTH `runIfLeader(slot)` + `runIfLeaderResult(slot)` override. Exposed v1.2.0 import 규칙 (top-level `org.jetbrains.exposed.v1.core.eq` 등) 준수.
- **Acceptance**:
  - T72 contract test 통과
  - Exposed 람다 implicit receiver 섀도잉 회피 (필드 충돌 시 로컬 변수 추출)
  - `update { }` / `insert { }` 람다 안 `slot.leaderId` 직접 사용 금지 (로컬 변수)

#### T31 [medium] — Exposed contract subclass + Testcontainers Postgres migration test
- **PR**: PR6
- **Files**:
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderIdContractTest.kt` (NEW)
  - `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcLeaderIdContractTest.kt` (NEW)
- **Depends-on**: T30
- **Description**: contract subclass. Exposed JDBC = sync only (R2DBC suspend). PR1 abstract 2종 사용.
- **Acceptance**:
  - `./gradlew :leader-exposed-jdbc:test :leader-exposed-r2dbc:test` clean

#### T52 [medium] — Exposed rollback test (Silent-failure [medium])
- **PR**: PR6
- **Files**:
  - `leader-exposed-jdbc/src/test/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedAuditColumnRollbackTest.kt` (NEW)
  - `leader-exposed-r2dbc/src/test/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2dbcAuditColumnRollbackTest.kt` (NEW)
- **Depends-on**: T29, T30
- **Description**: spec §13 + §17 T52. 새 코드 작성한 row (with `audit_leader_id`) 를 옛 코드 (no `auditLeaderId` reference) 가 SELECT/INSERT/UPDATE 정상 처리. Forward-compat 검증.
- **Acceptance**:
  - 새 코드로 row 작성 → 옛 코드 read OK
  - 옛 코드로 row 작성 (no audit) → 새 코드 read OK (`auditLeaderId = null`)
  - Testcontainers Postgres 사용

### PR6 acceptance + DoD

- [ ] `./gradlew :leader-exposed-core:build :leader-exposed-jdbc:build :leader-exposed-r2dbc:build` clean
- [ ] Testcontainers Postgres / MySQL 8.0 / H2 / SQL Server 모두 통과 (matrix)
- [ ] DML-only role 시 startup fail 검증 (T64)
- [ ] Rollback test (T52) clean
- [ ] README locale set: `leader-exposed-jdbc/README.{md,ko.md}` + `leader-exposed-r2dbc/README.{md,ko.md}` 에 `audit_leader_id ALTER` snippet + `ALTER TABLE` privilege 요구 명시 (T60)
- [ ] CHANGELOG 누적: Exposed `audit_leader_id VARCHAR(256)` 컬럼, startup probe, Flyway/Liquibase 사용자 의무 안내

#### T60 [low] — Exposed README ALTER DDL snippet + privilege 요구 명시
- **PR**: PR6
- **Files**:
  - `leader-exposed-jdbc/README.md` + `README.ko.md` (MODIFY)
  - `leader-exposed-r2dbc/README.md` + `README.ko.md` (MODIFY)
- **Depends-on**: T29, T64
- **Description**: spec §13 Round 2 N6/F4. ALTER DDL snippet + `ALTER TABLE` privilege 요구 + Flyway/Liquibase 사용자 가이드.
- **Acceptance**:
  - README 의 "Migration" 섹션에 SQL snippet (Postgres / MySQL 변형 포함)
  - locale set 둘 다 업데이트

### PR6 test list

| File | 검증 |
|------|------|
| `LeaderLockTableSchemaTest.kt` (확장) | `audit_leader_id` 컬럼 존재 |
| `LeaderGroupLockTableSchemaTest.kt` (확장) | 동일 |
| `ExposedJdbcSchemaInitializerTest.kt` (확장) | T64 probe 4-DB matrix |
| `ExposedJdbcLeaderIdContractTest.kt` | sync (JDBC) |
| `ExposedR2dbcLeaderIdContractTest.kt` | suspend (R2DBC) |
| `ExposedAuditColumnRollbackTest.kt` + R2DBC | T52 forward-compat |

---

## 7. PR7 — Phase H + I — AOP annotation/aspect + Spring autoconfig

- **depends-on**: PR1..PR6 (all backend modules)
- **scope**: `@LeaderElection.leaderId` + `@LeaderGroupElection.leaderId` annotation field, aspect `resolveLeaderId` fallback chain, `LeaderAopProperties.defaultLeaderId`, validator pre-parse + multi-annotation overlap reject, autoconfig wiring (`LeaderIdProvider` default bean, gauge bean), `LeaderElectionInfo` init invariant (T54), 6 recorder call site context propagation
- **freeze**: NO

> **PR7 의 핵심 의존성**: AOP 는 `LeaderAopMetricsContext.Identified(resolved.value, resolved.source)` 를 6 recorder call site 모두에 통과. 이 path 가 의미를 가지려면 PR2-6 모든 backend 가 BOTH `runIfLeader(slot)` AND `runIfLeaderResult(slot)` override 한 상태여야 함 → backend 가 `Elected(v, slot.leaderId)` 정확히 반환 → aspect 가 metric tag 에 올바른 audit 노출. PR2-6 미merge 상태에서 PR7 작업 진행은 silent-failure regression (bridge default fabrication).

### Phase H — AOP annotation + aspect

#### T55 [medium] — `SpelExpressionEvaluator` public `resolvePlaceholders` API + typo fix (Phase H pre-task)
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/spel/SpelExpressionEvaluator.kt` (MODIFY)
- **Depends-on**: PR6 merge
- **Description**: spec §D7 Round 2 F1. private `resolveplaceholder` (소문자 오타) → public `fun resolvePlaceholders(value: String): String` rename + expose. 기존 internal 호출 path 업데이트.
- **Acceptance**:
  - public method signature `fun resolvePlaceholders(value: String): String`
  - 기존 `evaluate()` 내부 호출 path 업데이트
  - 단위 테스트 — Spring `${...}` placeholder resolution 동작 확인
  - 다른 caller (aspect) 가 import 가능

#### T32 [low] — Annotation `leaderId: String = ""` 필드 추가
- **PR**: PR7
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderElection.kt` (MODIFY)
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderGroupElection.kt` (MODIFY)
- **Depends-on**: PR6 merge
- **Description**: spec §9. annotation 에 `val leaderId: String = ""` 추가 (INHERIT sentinel = empty string).
- **Acceptance**:
  - 기존 caller 컴파일 OK (default `""`)
  - KDoc 에 SpEL / literal / placeholder 3 형태 예제

#### T33 [medium] — `LeaderAopProperties.defaultLeaderId`
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/properties/LeaderAopProperties.kt` (MODIFY)
- **Depends-on**: T32
- **Description**: spec §9 — `val defaultLeaderId: String = ""`. YAML key `bluetape4k.leader.aop.default-leader-id`.
- **Acceptance**:
  - property mapping 정확
  - 단위 테스트 — `@ConfigurationProperties` binding

#### T34 [high] — `LeaderGroupElectionAspect.resolveLeaderId` + slot 구성 + 6 recorder call site context propagation
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderGroupElectionAspect.kt` (MODIFY)
- **Depends-on**: T32, T33, T55, T48, T49, T76, T69
- **Description**: spec §D7 + §9 + §17 T71 Round 7 codex #3.
  - `idProvider: LeaderIdProvider` 생성자 주입
  - `GroupAdviceMetadata` 에 `leaderIdExpression: String`, `leaderIdLiteral: String?` 추가
  - `resolveLeaderId(meta, method, args, target, lockName): ResolvedLeaderId` helper:
    - Step 0: Spring `${...}` placeholder (T55 API 사용)
    - Step 1: SpEL eval — 실패 시 `LeaderIdResolutionException` throw (always-RETHROW); blank 결과는 explicit-SPEL 미스컨피그 → throw (NOT silent AUTO downgrade, Round 10 N10-4)
    - Step 2: property `defaultLeaderId` fallback
    - Step 3: `safeNextLeaderId(idProvider, lockName)` — defensive
    - Final blank guard (Round 8 NEW-3): 모든 fallback 통과 후 blank 면 `LeaderIdResolutionException`
  - sync / suspend / Mono 3 분기 모두 `LeaderSlot(lockName, resolved.value)` 구성 후 `elector.runIfLeaderResult(slot) { ... }` 호출
  - 6 recorder call site (`onLockAttempt/Acquired/NotAcquired/TaskStarted/Finished/Failed`) 모두 `LeaderAopMetricsContext.Identified(resolved.value, resolved.source)` 통과 (Round 7 codex #3 — T71)
  - catch order: `CancellationException` → `LeaderIdResolutionException` (always-RETHROW) → broad `Exception` (failureMode 적용)
  - Mono 분기 `.onErrorResume(LeaderIdResolutionException::class.java) { Mono.error(it) }` (Round 17 N17-3 — `.onErrorResume(Throwable)` 단일 catch 금지)
  - suspend 분기 `fanOut` 사용 금지, manual try-catch + CE rethrow (Round 11 NEW-2)
  - `LeaderElectionInfo(lockName, true, resolved.value, resolved.source)` 생성 + `NotElected` 분기는 `(lockName, false, null, null)`
  - `runCatching {}` 사용 금지 (suspend path)
- **Acceptance**:
  - 7 case AOP integration test (T38) 통과
  - bridge WARN counter == 0 (PR2-6 backend 가 모두 override 된 상태)
  - `LeaderIdResolutionException` 가 `failureMode=SKIP/FAIL_OPEN_RUN` 정책 무관하게 throw
  - SpEL 실패 시 `MeterRegistry.counter("leader.aop.leader_id.resolution_failed")` 증가 (T66 상수 사용)

#### T35 [high] — `LeaderElectionAspect` mirror
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt` (MODIFY)
- **Depends-on**: T34
- **Description**: spec §9. T34 와 동일 패턴 — single leader aspect 에 mirror.
- **Acceptance**:
  - T34 와 동일 contract — single leader 경로
  - AOP integration test (T38) single 케이스 통과

#### T71 [high] — 6 recorder call site context propagation (T34/T35 의 일부, 명시 entry)
- **PR**: PR7
- **Files**: T34, T35 의 일부
- **Depends-on**: T34, T35
- **Description**: spec §17 T71 Round 7 codex #3 — spec 의 `onLockAcquired(..., ctx)` 만 언급된 부분을 6 메서드 모두 적용 명시. `resolved` 있으면 `Identified(resolved.value, resolved.source)`, 없으면 `Unknown`.
- **Acceptance**:
  - 6 call site 모두 context overload 호출 (grep 으로 검증)
  - 단위 테스트 — `recorder` mock 의 6 메서드 모두 호출 verify

#### T36 [medium] — `LeaderAnnotationValidatorBeanPostProcessor` preParse + overlap reject
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt` (MODIFY)
- **Depends-on**: T32, T55
- **Description**: spec §9 + §9.1 Round 3 R3-H4.
  - annotation `leaderId` 비어있지 않고 `LITERAL_PATTERN` 미일치 시 `spel.preParse(ann.leaderId)` 호출, 실패 → `IllegalStateException` (strict 모드 무관)
  - whitespace-only 거부 (Round 2 fix — `name` 필드 parity)
  - **Multi-annotation overlap reject (T59)**: 동일 메서드에 `@LeaderElection` + `@LeaderGroupElection` 공존 시 `IllegalStateException` throw — strict 모드 무관
- **Acceptance**:
  - 단위 테스트: literal / SpEL valid / SpEL invalid / placeholder / whitespace / overlap 6 케이스
  - Overlap reject 시 message: `"Method X#Y has both @LeaderElection and @LeaderGroupElection — must pick one"`

#### T59 [medium] — Multi-annotation overlap validator (T36 의 일부)
- **PR**: PR7
- **Files**: T36 의 일부
- **Depends-on**: T36
- **Description**: spec §9.1 + §17 T59. T36 에서 통합 작업. 본 entry 는 spec mapping 보존.
- **Acceptance**:
  - T36 acceptance 의 overlap 케이스 동일

### Phase I — Spring auto-config

#### T37 [medium] — `LeaderAopFactoryAutoConfiguration` — default `LeaderIdProvider` bean
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopFactoryAutoConfiguration.kt` (MODIFY)
- **Depends-on**: T34, T35
- **Description**: spec §9.
  - `@Bean @ConditionalOnMissingBean(LeaderIdProvider::class) fun leaderIdProvider(): LeaderIdProvider = RandomLeaderIdProvider.Default`
  - aspect bean 에 `idProvider` 주입
- **Acceptance**:
  - `@SpringBootTest` 에서 default bean 등록 확인
  - 사용자가 `LeaderIdProvider` bean 정의 시 default 미등록 확인

#### T68b [medium] — `LeaderElectorFactory.targetElectorClass` SPI + AutoConfig probe
- **PR**: PR7
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/spi/LeaderElectorFactory.kt` (MODIFY — `targetElectorClass: KClass<out LeaderElector>` accessor 추가)
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt` (MODIFY — probe 추가)
- **Depends-on**: T37
- **Description**: spec §D6 T68b Round 7 N7-3 + Round 7 codex #4. `LeaderAopAutoConfiguration` 가 startup 시 모든 등록 factory 의 `targetElectorClass` reflection 검사 — `runIfLeader(LeaderSlot, ...)` AND `runIfLeaderResult(LeaderSlot, ...)` declared 여부. 미override 시 WARN log (factory + targetClass + missing method).
- **Acceptance**:
  - SPI accessor 추가 후 기존 factory 모두 default 또는 명시 override
  - 단위 테스트 — mock factory 의 미override 시 WARN log capture
  - PR1 의 detekt rule (T83) 과 함께 두 layer 보호

#### T69b [medium] — `LeaderAopAutoConfiguration` recorder probe (after `LeaderMicrometerAutoConfiguration`)
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt` (MODIFY)
- **Depends-on**: T69, T74, T37
- **Description**: spec §D6 T69b Round 6 H2 + Round 7 codex #4. `@AutoConfigureAfter(LeaderMicrometerAutoConfiguration::class)`. `ObjectProvider<LeaderAopMetricsRecorder>` 로 모든 등록 recorder bean 의 6 context overload override 여부 reflection 검사. 미override 시 WARN log + `bluetape4k.leader.aop.leader-id.metrics-required=true` 시 startup fail.
- **Acceptance**:
  - default property `false` (warn only)
  - 단위 테스트 — custom recorder (override 없음) + property true 시 startup fail
  - WARN message 에 recorder class name + missing method 명시

#### T73 [medium] — T68b + T69b 의 PR7 Phase I 명시 + AutoConfig 순서
- **PR**: PR7
- **Files**: T68b, T69b 의 일부
- **Depends-on**: T68b, T69b
- **Description**: spec §17 T73 Round 7 codex #4. `LeaderMicrometerAutoConfiguration` 후 `LeaderAopAutoConfiguration` 실행되도록 `@AutoConfigureAfter` chain 검증. spec 의 AutoConfig 순서:
  ```
  LeaderElectionAutoConfiguration
  LeaderAopFactoryAutoConfiguration  (after)
  LeaderMicrometerAutoConfiguration  (after)
  LeaderAopAutoConfiguration         (after)
  ```
- **Acceptance**:
  - `AutoConfigurationImportSelector` ordering 검증 단위 테스트
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 4 항목 등록

#### T70-part2 [low] — Micrometer gauge bean registration (PR7 부분)
- **PR**: PR7
- **Files**:
  - `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/LeaderBridgeMetrics.kt` (NEW — helper)
  - `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorder.kt` (MODIFY — context overload override)
  - `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/autoconfigure/LeaderMicrometerAutoConfiguration.kt` (MODIFY — gauge bean)
- **Depends-on**: T70-part1, T75, T74, T69
- **Description**: spec §17 T70 Round 14 NEW-14-3 + Round 15 + Round 16 NEW-16-2.
  - `LeaderBridgeMetrics.register(registry)` public helper:
    ```kotlin
    Gauge.builder(MicrometerNames.GAUGE_BRIDGE_DROPPED) {
        LeaderElectorBridgeLog.global().droppedAuditCount().toDouble()
    }.register(registry)
    Gauge.builder(MicrometerNames.GAUGE_BRIDGE_RESULT_DROPPED) {
        LeaderElectorBridgeLog.global().droppedResultBridgeCount().toDouble()
    }.register(registry)
    ```
  - `MicrometerLeaderAopMetricsRecorder` 가 6 context overload override 하여 `TAG_LEADER_ID` + `TAG_LEADER_ID_SOURCE` tag 기록
  - cardinality policy: AUTO source 는 hash-prefix; LITERAL/PROPERTY 는 full; SPEL 은 hash-prefix (or full via `bluetape4k.leader.aop.metrics.allow-full-spel-leader-id=true`)
  - `LeaderMicrometerAutoConfiguration` 에 `@Bean @ConditionalOnBean(MeterRegistry::class) fun leaderBridgeMetrics(...)` 별도 bean (recorder bean 과 independent — Round 15 codex)
- **Acceptance**:
  - 2 gauge bean 등록 (Testcontainers 없이 single-JVM `SimpleMeterRegistry` 통합 테스트)
  - setGlobal swap 시 lambda 가 새 instance counter re-read
  - 단위 테스트 — AUTO / LITERAL / SPEL / PROPERTY 4 cardinality 분기

#### T84 [low] — `LeaderRecorderContextDropLog` MeterRegistry-absent fallback log parity (Round 17 N17-4)
- **PR**: PR7
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt` (MODIFY)
  - 또는 `leader-micrometer/.../LeaderMicrometerAutoConfiguration.kt` (MODIFY) — non-conditional `@Bean` startup INFO log
- **Depends-on**: T70-part2
- **Description**: spec Round 17 N17-4 + Round 16 NEW-16-2 + Round 17 codex NEW-17-2. MeterRegistry 부재 시:
  - `LeaderElectorBridgeLog` (T75) 와 `LeaderRecorderContextDropLog` (T74) 둘 다 동일하게 startup INFO log emit
  - log message: `"MeterRegistry absent; bridge drop gauges + recorder drop log not registered. Query LeaderElectorBridgeLog.global().droppedAuditCount() / droppedResultBridgeCount() / LeaderRecorderContextDropLog.global().droppedCount() directly for monitoring."`
- **Acceptance**:
  - MeterRegistry 부재 ApplicationContext 테스트 — INFO log 출력
  - 직접 query path 동작 (gauge 없이도 counter 접근)

#### T78 [medium] — `resolveLeaderId` final blank guard (T34 의 일부, 명시 entry)
- **PR**: PR7
- **Files**: T34 의 일부 (`LeaderGroupElectionAspect.kt`, `LeaderElectionAspect.kt`)
- **Depends-on**: T34, T35
- **Description**: spec §17 T78 Round 8 NEW-3. fallback chain 4 level 모두 통과 후에도 결과 blank 면 `LeaderIdResolutionException` throw. `Identified.init { leaderId.requireNotBlank }` 도달 차단.
- **Acceptance**:
  - T34 단위 테스트: 모든 fallback (`literal=""`, SpEL skipped, property=`""`, provider returns `""`) 시나리오 → `LeaderIdResolutionException`

### PR7 acceptance + DoD

- [ ] `./gradlew :leader-spring-boot:build :leader-micrometer:build` clean
- [ ] AOP integration test (T38 — PR8 으로 이관) 의 prep — `LeaderIdAopIntegrationTest` 7 case 통과 가능 상태
- [ ] AutoConfig 순서 검증 (T73)
- [ ] T54 `LeaderElectionInfo` init invariant 도입 — 모든 backend test green
- [ ] bridge WARN counter == 0 (PR2-6 backend 가 모두 override 된 상태에서 aspect 사용)
- [ ] README locale set: `leader-spring-boot/README.{md,ko.md}` annotation `leaderId` 예시 (literal / SpEL / placeholder) 섹션
- [ ] CHANGELOG 누적: annotation field, aspect resolution chain, validator overlap reject (BREAKING), recorder context overload, Micrometer tag

### PR7 test list (AOP slice tests)

| File | 검증 |
|------|------|
| `SpelExpressionEvaluatorTest.kt` (확장) | T55 public `resolvePlaceholders` |
| `LeaderGroupElectionAspectResolveLeaderIdTest.kt` (NEW) | T34 — 7 case (placeholder/literal/SpEL/property/provider/SpEL-blank/all-blank) |
| `LeaderElectionAspectResolveLeaderIdTest.kt` (NEW) | T35 — single leader mirror |
| `LeaderAnnotationValidatorOverlapTest.kt` (NEW) | T59 — multi-annotation reject |
| `LeaderAnnotationValidatorLeaderIdTest.kt` (확장) | T36 — preParse + whitespace + invalid SpEL |
| `LeaderAopFactoryAutoConfigurationTest.kt` (확장) | T37 — default bean + user override |
| `LeaderAopAutoConfigurationProbeTest.kt` (NEW) | T68b + T69b — factory + recorder probe |
| `MicrometerLeaderAopMetricsRecorderContextTest.kt` (NEW) | T70-part2 — 6 overload + tag cardinality |
| `LeaderBridgeMetricsTest.kt` (NEW) | T70-part2 — 2 gauge binding, setGlobal swap |

### PR7 README / CHANGELOG impact

- `leader-spring-boot/README.{md,ko.md}`: "Audit Identity (AOP)" 섹션 — annotation 예시, `LeaderIdProvider` bean override, `default-leader-id` property
- `CHANGELOG.md`:
  - `### Added` — `@LeaderElection.leaderId`, `@LeaderGroupElection.leaderId`, `LeaderAopProperties.defaultLeaderId`, default `LeaderIdProvider` bean, factory + recorder probe, Micrometer gauge + tag
  - `### Changed` — recorder API 6 context overload (backward-compat default), `LeaderElectionInfo` init invariant (PR7 도입)
  - `### Breaking` — multi-annotation overlap reject (`@LeaderElection` + `@LeaderGroupElection` 동일 메서드 → startup fail)

---

## 8. PR8 — Phase J + K — Cross-backend tests + docs/CHANGELOG

- **depends-on**: PR1..PR7
- **scope**: cross-backend matrix integration tests (nightly profile), AOP integration test, metrics tag test, README locale set 전체 업데이트, CHANGELOG `Unreleased` → release headline 작성
- **freeze**: NO

### Phase J — Integration tests

#### T38 [high] — `LeaderIdAopIntegrationTest` — 7 case
- **PR**: PR8
- **Files**:
  - `leader-spring-boot/src/integrationTest/kotlin/io/bluetape4k/leader/spring/integration/LeaderIdAopIntegrationTest.kt` (NEW)
- **Depends-on**: PR7 merge
- **Description**: spec §10 + §15 + Round 5 codex H3. `ApplicationContextRunner` 사용. 7 case:
  - 1. `annotation literal leaderId flows through SpEL fast-path`
  - 2. `annotation SpEL expression evaluates with method args`
  - 3. `empty annotation + property default-leader-id flows as fallback`
  - 4. `all empty + default provider yields random`
  - 5. `user-provided LeaderIdProvider bean overrides default`
  - 6. `invalid SpEL leaderId fails context startup` (validator)
  - 7. `SpEL runtime evaluation failure throws LeaderIdResolutionException regardless of failureMode` (always-RETHROW)
- **Acceptance**:
  - 7 케이스 모두 통과
  - Local backend 사용 (Testcontainers 불필요)
  - case 7 — `failureMode=SKIP` / `FAIL_OPEN_RUN` 모두 throw 검증

#### T39 [medium] — `LeaderIdMetricsTagsTest` — Micrometer tag 검증
- **PR**: PR8
- **Files**:
  - `leader-micrometer/src/integrationTest/kotlin/io/bluetape4k/leader/micrometer/integration/LeaderIdMetricsTagsTest.kt` (NEW)
- **Depends-on**: T70-part2
- **Description**: spec §10. Micrometer `SimpleMeterRegistry` + `LeaderElector` (Local) — `leader.id` + `leader.id.source` tag 검증. 4 source 별 (LITERAL/SPEL/PROPERTY/AUTO) cardinality 분기 검증.
- **Acceptance**:
  - `registry.find(counterName).tag("leader.id.source", "LITERAL").counter()` 존재 검증
  - AUTO source 시 tag value 가 hash-prefix (4자) 인지 검증

#### T40 [medium] — `LeaderIdMultiBackendIntegrationTest` — cross-backend smoke
- **PR**: PR8
- **Files**:
  - `leader-spring-boot/src/integrationTest/kotlin/io/bluetape4k/leader/spring/integration/LeaderIdMultiBackendIntegrationTest.kt` (NEW)
- **Depends-on**: PR7 merge, all backend modules
- **Description**: spec §10 + §17 T40. 모든 backend (Local / Lettuce / Redisson / Mongo / Hazelcast / ZK / Exposed-JDBC / Exposed-R2DBC) 에서 audit identity end-to-end 통과 — `slot.leaderId == lease.auditLeaderId == handle.auditLeaderId == result.leaderId == metric tag value`. nightly profile (CI matrix).
- **Acceptance**:
  - 8 backend × 1 happy-path test
  - nightly-tests.yml 에 추가
  - bridge WARN counter == 0 (모든 backend 가 BOTH override 검증)

#### T85 [low] — Bridge double-count Prometheus query rubric in T70 KDoc
- **PR**: PR8
- **Files**:
  - `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/LeaderBridgeMetrics.kt` (MODIFY — KDoc)
  - `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerNames.kt` (MODIFY — KDoc)
- **Depends-on**: T70-part2
- **Description**: spec §13 Round 15 NEW-15-3 + Round 16 NEW-16-1 + Round 17 N17-2 / NEW-17-2. KDoc 명시:
  - **`rate()` 사용 금지** — Gauge 에 부적합 (Prometheus 는 `rate()` 를 counter 전용으로 정의)
  - **권장 query**: `idelta(metric[1m])` 또는 raw gauge inspection + `setGlobal()` swap log timestamp correlation
  - **Bridge double-count case**: backend 가 `runIfLeader(slot)` 만 override + `runIfLeaderResult(slot)` 미override → 두 WARN + 두 counter 동시 증가 (의도된 동작, T75 KDoc 에서 이미 명시; T85 는 Prometheus query rubric 만 추가)
- **Acceptance**:
  - KDoc Prometheus query 예제 포함 (idelta + raw + correlation)
  - README 의 metric 섹션에 동일 가이드 cross-link

### Phase K — Docs

#### T41 [medium] — `leader-core/README` "Audit Identity" 섹션 + Mermaid sequence diagram
- **PR**: PR8
- **Files**:
  - `leader-core/README.md` (MODIFY — Architecture section)
  - `leader-core/README.ko.md` (MODIFY — Architecture section)
- **Depends-on**: PR7 merge
- **Description**: spec §11. Mermaid sequence diagram (spec §7 data flow 시각화) + audit identity 의미 + `LeaderSlot` + `LeaderIdProvider` 3 impl + `LeaderIdSource` provenance + 사용 예제.
- **Acceptance**:
  - English README + 한국어 README 동일 구조
  - Mermaid `sequenceDiagram` (Vega-Lite 금지)
  - Architecture-first 구조 (CLAUDE.md 규칙)

#### T42 [medium] — `leader-spring-boot/README` annotation 예시
- **PR**: PR8
- **Files**:
  - `leader-spring-boot/README.md` (MODIFY)
  - `leader-spring-boot/README.ko.md` (MODIFY)
- **Depends-on**: PR7 merge
- **Description**: spec §11. `@LeaderElection(leaderId="...")` / SpEL / placeholder 예시 + `LeaderIdProvider` bean override 예시 + `default-leader-id` property + multi-annotation overlap 금지 (BREAKING).
- **Acceptance**:
  - 4 사용 패턴 예시 (literal / SpEL / placeholder / provider override)
  - English KDoc snippet 포함

#### T43 [low] — 각 backend README locale set
- **PR**: PR8
- **Files**:
  - `leader-redis-lettuce/README.{md,ko.md}` (이미 PR2 에서 일부 업데이트)
  - `leader-redis-redisson/README.{md,ko.md}` (이미 PR2)
  - `leader-mongodb/README.{md,ko.md}` (이미 PR3)
  - `leader-hazelcast/README.{md,ko.md}` (이미 PR4)
  - `leader-zookeeper/README.{md,ko.md}` (이미 PR5)
  - `leader-exposed-jdbc/README.{md,ko.md}` (이미 PR6 T60)
  - `leader-exposed-r2dbc/README.{md,ko.md}` (이미 PR6 T60)
- **Depends-on**: PR2..PR6 merge
- **Description**: spec §11 — 각 backend 별 audit metadata location 노트가 이미 각 PR 에서 추가됨. PR8 은 cross-link + consistency 검토만.
- **Acceptance**:
  - 7 backend README 모두 "Audit Identity" 한 섹션 보유
  - English + 한국어 locale 동기화

#### T44 [low] — Root README feature highlight
- **PR**: PR8
- **Files**:
  - `README.md` (MODIFY)
  - `README.ko.md` (MODIFY)
- **Depends-on**: T41, T42
- **Description**: spec §11. Root README 의 "Features" 섹션에 "Per-election audit identity (`@LeaderElection.leaderId`, `@LeaderGroupElection.leaderId`)" highlight + `leader-core/README` cross-link.
- **Acceptance**:
  - Root README 둘 다 highlight 추가
  - cross-link to `leader-core/README#audit-identity`

#### T45 [medium] — `CHANGELOG.md` — Unreleased headline
- **PR**: PR8
- **Files**:
  - `CHANGELOG.md` (MODIFY — release headline 정리)
- **Depends-on**: PR1..PR7 의 CHANGELOG 누적
- **Description**: spec §11 + §13. PR1-7 의 CHANGELOG 누적을 단일 `## [Unreleased]` 섹션으로 통합. Release-note headline:
  - **BREAKING (audit)**: `LeaderLease.leaderId` deprecated → `auditLeaderId` (per-election) + `nodeId` (JVM node). `serialVersionUID` bumped 1L → 2L.
  - **BREAKING (validator)**: Multi-annotation `@LeaderElection` + `@LeaderGroupElection` 동일 메서드 공존 시 startup-fail.
  - **MIGRATION (Exposed)**: `audit_leader_id VARCHAR(256) NULL` 컬럼 `bluetape4k_leader_locks` + `bluetape4k_leader_group_locks` 양쪽에 ALTER. `ALTER TABLE` privilege 의무.
  - **ADDED**: `LeaderSlot`, `LeaderIdProvider` + 3 impl, `LeaderIdSource`, `LeaderIdResolutionException`, audit propagation 모든 backend, Micrometer `leader.id` + `leader.id.source` tag, Aspect resolution chain.
  - **MOST**: 모든 leader-* 모듈 + downstream consumer 동시 recompile 필요 (Architect [low]).
- **Acceptance**:
  - CHANGELOG 4 section (BREAKING / MIGRATION / ADDED / NOTE)
  - issue #72 reference

#### T46 [low] — English KDoc 보강 (모든 변경 public symbol)
- **PR**: PR8
- **Files**: 변경된 public symbol 의 KDoc 누락 보강 — sweep
- **Depends-on**: PR7 merge
- **Description**: spec §15 acceptance + CLAUDE.md KDoc 요구사항. `## 동작/계약` 섹션 + `kotlin` 예제 블록 의무.
- **Acceptance**:
  - `./gradlew dokka` 또는 IDE 의 KDoc 검사 — public symbol 0 missing

#### T80 [low] — CHANGELOG `### CHANGELOG draft` 섹션 정리
- **PR**: PR8
- **Files**: T45 의 일부
- **Depends-on**: T45
- **Description**: spec §17 T80 Round 8 codex #4 / N8-3. PR1-7 동안 누적된 CHANGELOG cell 을 sub-section 으로 재구성.
- **Acceptance**:
  - T45 의 4 section 구조에 통합

#### T81 [medium] — **deferred (post-PR8)** — AUTO source LRU throttle redesign
- **PR**: **deferred (follow-up issue)**
- **Files**: (future)
  - `leader-core/.../LeaderSlot.kt` (add `source: LeaderIdSource?` field)
  - `leader-core/.../LeaderElectorBridgeLog.kt` (source-aware throttle key)
- **Depends-on**: PR8 merge → follow-up issue 등록
- **Description**: spec Round 16 architect N16-2 + Round 17 codex NEW-17-3. AUTO source 의 unique-per-call leaderId 가 LRU churn → unthrottled WARN flood. follow-up issue 로 다음 작업:
  - `LeaderSlot` 에 `source: LeaderIdSource?` 필드 additive (default null — backward-compat)
  - `LeaderElectorBridgeLog` throttle key 를 source-aware 로 분리:
    - LITERAL / PROPERTY / SPEL → per-leaderId (현재 동작)
    - AUTO → per-class (collapse — unique leaderId 무시)
- **Acceptance**:
  - PR8 merge 후 follow-up issue 등록 (issue title: `feat(leader-id): AUTO source LRU throttle redesign — source-aware key`)
  - PR8 의 release note 에 deferred 항목 1행 포함

### PR8 acceptance + DoD

- [ ] `./gradlew build` (root) clean
- [ ] nightly-tests.yml — multi-backend matrix 통과
- [ ] T38 7 case + T39 metrics + T40 multi-backend smoke 모두 clean
- [ ] README 8 모듈 (`leader-core`, `leader-spring-boot`, `leader-redis-lettuce`, `leader-redis-redisson`, `leader-mongodb`, `leader-hazelcast`, `leader-zookeeper`, `leader-exposed-jdbc`, `leader-exposed-r2dbc`) + root 모두 locale set 업데이트
- [ ] CHANGELOG 4 section release headline
- [ ] T81 follow-up issue 등록 ID 명시
- [ ] 전체 PR 시리즈 (PR1-PR8) 의 spec §15 DoD 36 항목 모두 [x]

### PR8 test list

| File | 검증 |
|------|------|
| `LeaderIdAopIntegrationTest.kt` | T38 — 7 case |
| `LeaderIdMetricsTagsTest.kt` | T39 — Micrometer tag |
| `LeaderIdMultiBackendIntegrationTest.kt` | T40 — 8 backend smoke |

### PR8 README / CHANGELOG impact

전체 PR 시리즈 누적 — spec §11 의 모든 row 가 본 PR 에서 finalize. release-note headline (T45) 가 단일 reference point.

---

## 9. Cross-cutting concerns

### 9.1 testFixture API freeze 가이드

PR1 시점에 `2026-05-13-leader-id-testfixture-freeze.md` 가 명시한 다음 surface 는 PR1.5 commit hash 이후 모든 PR2-6 에서 frozen:

| Surface | 구체 시그니처 |
|---------|---------------|
| `AbstractLeaderIdContractTest` | `abstract fun createGroupElector(maxLeaders: Int): LeaderGroupElector` + `abstract fun createSingleElector(): LeaderElector` |
| `AbstractSuspendLeaderIdContractTest` | `abstract fun createGroupElector(maxLeaders: Int): SuspendLeaderGroupElector` + suspend single |
| `AbstractAsyncLeaderIdContractTest` | `abstract fun createGroupElector(maxLeaders: Int): AsyncLeaderGroupElector` + async single |
| `AbstractVirtualThreadLeaderIdContractTest` | VT variants |
| `LeaderLockHandle.Real` constructor | `(identity, token, acquiredAtNanos, slotId, acquiringThreadId, reentryDepth, extendDelegate, auditLeaderId)` — END positional |
| `LeaderLockHandle.real(...)` factory | 동일 positional |

### 9.2 변경 발생 시 절차

PR2-6 작업 중 위 surface 변경 필요 시:

1. coordinator (PR1 author) 에게 통보
2. 모든 open PR (PR2-6) rebase 조율
3. `2026-05-13-leader-id-testfixture-freeze.md` 의 frozen hash 갱신 (새 develop hash)
4. `CHANGELOG.md` 의 `## [Unreleased]` 에 "internal API change — testFixture contract updated to <new hash>" 항목 추가

### 9.3 bluetape4k-patterns conformance checklist (모든 PR 공통)

- [ ] `CancellationException` 항상 `catch (Exception)` 앞에 명시 catch + rethrow
- [ ] `runCatching {}` 안 suspend 호출 0건 (grep 검증)
- [ ] `KLogging` companion 사용 — hot path `nextLeaderId()` 에는 log 금지
- [ ] `requireNotBlank` / `requireGt` 등 fail-fast — `IllegalArgumentException`
- [ ] `assertXxx()` 사용 0건 (deprecated)
- [ ] `@Synchronized` / `synchronized {}` 0건 (VT parity) → `reentrantLock()`
- [ ] `!!` 0건 → `?.` / `?:` / `requireNotNull()`
- [ ] `atomicfu` 메서드 local 0건
- [ ] 동종 타입 paired 파라미터 → data class wrapping (`LeaderSlot` 가 primary 예시)
- [ ] Test concurrency tester (`MultithreadingTester` / `SuspendedJobTester` / `StructuredTaskScopeTester`) 의무
- [ ] 직접 `Thread` / `Executors` / `coroutineScope { launch }` 0건
- [ ] `assertFailsWith<T> { }` / `coInvoking shouldThrow` — `invoking shouldThrow` / `assertThrows` / `kotlin.test.assertFailsWith` 0건
- [ ] English KDoc + `## Contract` 섹션 + `kotlin` 예제 (public symbol)

### 9.4 Mandatory build/test commands per task

```bash
# Per task GREEN step
./gradlew :<module>:build           # full module build (compile + test + detekt + kover)
./gradlew :<module>:test            # only test
./gradlew detekt                    # detekt sweep

# Per PR merge gate
./gradlew build -x test --no-daemon # root compile sanity
./gradlew :<module>:koverHtmlReport # coverage check
./gradlew :<module>:koverVerify     # threshold gate

# CI sweep
./gradlew clean build               # root full build
./gradlew nightly                   # nightly profile (matrix backends)
```

### 9.5 Worktree / git workflow

```bash
# PR2-6 worker 의 worktree 분기 (PR1.5 hash 기반)
cd /Users/debop/work/bluetape4k/bluetape4k-leader
git worktree add .worktrees/feat-leader-id-pr2-lettuce-redisson <PR1.5 hash> -b feat/leader-id-pr2-lettuce-redisson-72
cd .worktrees/feat-leader-id-pr2-lettuce-redisson
# 작업 후 push → PR --base develop
```

- 모든 PR `--base develop` (main 으로 직접 PR 금지)
- squash-merge
- one issue = one PR (본 작업은 issue #72 1개, 8 PR 모두 동일 issue 참조)
- Commit message 한국어 + prefix (`feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`)

### 9.6 Spring AutoConfig 규칙 (PR7 적용)

- `@ConditionalOnClass(name = [...])` — compileOnly 클래스 가드 (`MeterRegistry`, `MicrometerRecorder` 등)
- AutoConfigure 순서 — 별도 클래스 분리 + `AutoConfiguration.imports` 등록
- `@ConditionalOnProperty` — 모든 AutoConfig phase 클래스에 중복 적용
- `INHERIT` sentinel — `failureMode` 와 `leaderId: String = ""` 양쪽 적용

### 9.7 Exposed 1.2.0 import 규칙 (PR6 적용)

```kotlin
// ✅ top-level
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.exists

// ❌ deprecated ERROR
// import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
```

- 람다 implicit receiver 섀도잉 — `update { it[auditLeaderId] = localValue }` 로 로컬 변수 추출

---

## 10. Complexity 분포

### 10.1 Task ID → PR → complexity mapping

| Task ID | Complexity | PR | Title 요약 |
|---------|------------|----|-----------|
| T1 | low | PR1 | `LeaderSlot` value object |
| T2 | low | PR1 | `LeaderIdProvider` fun interface |
| T3 | low | PR1 | `RandomLeaderIdProvider` |
| T4 | low | PR1 | `HostnamePidLeaderIdProvider` |
| T5 | low | PR1 | `CompositeLeaderIdProvider` |
| T6 | medium | PR1 | `LeaderLease.nodeId` additive |
| T7 | medium | PR1 | `LeaderRunResult.Elected.leaderId` + `@JvmOverloads` |
| T8 | medium | PR1 | (= T56) `LeaderLockHandle.Real.auditLeaderId` |
| T9 | low | PR1 | `LeaderElectionInfo.leaderId/leaderIdSource` additive |
| T10 | high | PR1 | (= T68 sync) interface bridge default |
| T11 | high | PR1 | (= T68 async) |
| T12 | high | PR1 | (= T68 VT) |
| T13 | high | PR1 | (= T68 suspend) |
| T14 | high | PR1 | `LocalLeaderStateRegistry` signature 분리 |
| T15 | high | PR1 | Local 10 elector propagate |
| T16 | high | PR1 | `AbstractLeaderIdContractTest` × 4 |
| T17 | medium | PR1 | Local contract subclass |
| T18 | high | PR2 | `LettuceSlotTokenGroup` Lua HSET |
| T19 | high | PR2 | Lettuce 4 elector propagate |
| T20 | high | PR2 | Redisson 4 elector + RMap |
| T21 | medium | PR2 | Lettuce + Redisson contract subclass |
| T22 | high | PR3 | `MongoLock` schema |
| T23 | high | PR3 | Mongo 4 elector propagate |
| T24 | medium | PR3 | Mongo contract subclass |
| T25 | high | PR4 | Hazelcast 4 elector + IMap |
| T26 | medium | PR4 | Hazelcast contract subclass |
| T27 | high | PR5 | ZK 4 elector + znode JSON |
| T28 | medium | PR5 | ZK contract subclass |
| T29 | medium | PR6 | Exposed 2 table audit column |
| T30 | high | PR6 | Exposed JDBC + R2DBC propagate |
| T31 | medium | PR6 | Exposed contract subclass |
| T32 | low | PR7 | annotation `leaderId` field |
| T33 | medium | PR7 | `LeaderAopProperties.defaultLeaderId` |
| T34 | high | PR7 | `LeaderGroupElectionAspect.resolveLeaderId` + 6 recorder |
| T35 | high | PR7 | `LeaderElectionAspect` mirror |
| T36 | medium | PR7 | validator preParse + overlap |
| T37 | medium | PR7 | autoconfig default `LeaderIdProvider` bean |
| T38 | high | PR8 | `LeaderIdAopIntegrationTest` 7 case |
| T39 | medium | PR8 | `LeaderIdMetricsTagsTest` |
| T40 | medium | PR8 | `LeaderIdMultiBackendIntegrationTest` |
| T41 | medium | PR8 | `leader-core/README` Audit Identity |
| T42 | medium | PR8 | `leader-spring-boot/README` annotation |
| T43 | low | PR8 | 7 backend README cross-link |
| T44 | low | PR8 | Root README highlight |
| T45 | medium | PR8 | CHANGELOG headline |
| T46 | low | PR8 | English KDoc sweep |
| T47 | low | PR1 | `LeaderIdSource` enum |
| T48 | medium | PR1 | `LeaderIdResolutionException` |
| T49 | medium | PR1 | `safeNextLeaderId` + `@LeaderInternalApi` |
| T50 | high | PR1 | Phase A0 grep gate script |
| T51 | medium | PR1 | reentrant audit preservation test |
| T52 | medium | PR6 | Exposed rollback test |
| T53 | low | PR1 | (= T67) testFixture freeze docs |
| T54 | medium | PR7 | `LeaderElectionInfo` init invariant |
| T55 | medium | PR7 | `SpelExpressionEvaluator` public API |
| T56 | high | PR1 | `LeaderLockHandle.Real` END positional |
| T57 | medium | PR1 | `LeaderLease` rename + serialVersionUID |
| T58 | low | PR1 | `@JvmOverloads` verification test |
| T59 | medium | PR7 | multi-annotation overlap validator |
| T60 | low | PR6 | Exposed README ALTER DDL |
| T61 | medium | PR7 | (= T70-part2 cardinality 분기 일부) Micrometer cardinality policy |
| T62 | low | PR7 | `fanOut runCatching` audit clarification (KDoc) |
| T63 | low | PR1 | `@LeaderInternalApi` annotation |
| T64 | high | PR6 | Exposed startup runtime probe |
| T65 | medium | PR1 | `LeaderElectionInfo.validate()` opt-in |
| T66 | low | PR1 | Micrometer counter name 상수 |
| T67 | low | PR1 | testFixture freeze docs (= T53) |
| T68 | high | PR1 | 8 interface bridge default |
| T68b | medium | PR7 | factory probe |
| T68c | low | PR1 | (= T83) detekt rule |
| T69 | medium | PR1 | recorder 6 context overload |
| T69b | medium | PR7 | recorder probe |
| T70 (split) | low (part1 PR1) + low (part2 PR7) | PR1 + PR7 | MicrometerNames 상수 + gauge bean |
| T71 | high | PR7 | (T34/T35 일부) 6 recorder call site |
| T72 | high | PR2-PR6 | BOTH override 의무 contract test (split per backend) |
| T73 | medium | PR7 | AutoConfig 순서 |
| T74 | medium | PR1 | `LeaderRecorderContextDropLog` |
| T75 | medium | PR1 | `LeaderElectorBridgeLog` |
| T76 | medium | PR1 | `LeaderAopMetricsContext` sealed |
| T77 | high | PR1 | sealed 호환 test |
| T78 | medium | PR7 | (T34 일부) final blank guard |
| T79 | low | PR1 | setGlobal swap log + docs |
| T80 | low | PR8 | CHANGELOG section 재구성 |
| T81 | medium | **deferred** | AUTO source LRU throttle redesign |
| T82 | medium | PR1 | super.X enforcement contract test |
| T83 | low | PR1 | detekt rule super.X forbid |
| T84 | low | PR7 | MeterRegistry-absent fallback log |
| T85 | low | PR8 | Prometheus query rubric KDoc |

### 10.2 Complexity 분포 합계

| Complexity | Count | PR 별 분포 |
|------------|-------|-----------|
| **high** | **23** | PR1: 10 (T10/T11/T12/T13/T14/T15/T16/T50/T56/T68/T77) / PR2: 3 (T18/T19/T20) / PR3: 2 (T22/T23) / PR4: 1 (T25) / PR5: 1 (T27) / PR6: 2 (T30/T64) / PR7: 4 (T34/T35/T71/T72-split) / PR8: 1 (T38) |
| **medium** | **34** | PR1: 13 (T6/T7/T8/T17/T48/T49/T51/T57/T65/T69/T74/T75/T76/T82) / PR2: 1 (T21) / PR3: 1 (T24) / PR4: 1 (T26) / PR5: 1 (T28) / PR6: 4 (T29/T31/T52) / PR7: 9 (T33/T36/T37/T54/T55/T59/T61/T68b/T69b/T73/T78) / PR8: 3 (T39/T40/T41/T42/T45) / deferred: 1 (T81) |
| **low** | **28** | PR1: 12 (T1/T2/T3/T4/T5/T9/T47/T58/T63/T66/T67(=T53)/T68c(=T83)/T70-part1/T79) / PR6: 1 (T60) / PR7: 3 (T32/T62/T70-part2/T84) / PR8: 4 (T43/T44/T46/T80/T85) |

> **합계**: 23 high + 34 medium + 28 low = **85 task** (deferred T81 포함). target band (high 20-25 / medium 30-35 / low 25-30 / total ~85) **충족**.

> **Note**: 일부 task ID 는 다른 task 의 일부로 통합되어 중복 counting 회피:
> - T8 = T56 (작업 동일, counting 1회)
> - T10/T11/T12/T13 = T68 의 sync/async/VT/suspend split (counting: 4 high in PR1 total, but T68 is single physical task — counted T68 as 1)
> - T53 = T67 (counting 1회)
> - T68c = T83 (counting 1회)
> - T61 ⊂ T70-part2 (counting 1회)
> - T62 = KDoc note in T34 (counting 1회)
> - T71 ⊂ T34/T35 (counting 1회 as PR7 high — 그러나 spec mapping 유지 목적으로 entry 보존)
> - T72 = backend BOTH override 검증 (PR2-PR6 5개 sub-task — counting 1 logical task split 5)
> - T78 ⊂ T34 (counting 1회)
> 통합 후 **unique physical task**: ~70 (spec 의 80 task + Round 17 의 5 task = 85 의 logical task ID, 실제 git commit 단위로는 ~70).

### 10.3 PR-level task counts

| PR | High | Medium | Low | Total |
|----|------|--------|-----|-------|
| PR1 | 10 | 13 | 12 | 35 |
| PR2 | 3 | 1 | 0 | 4 |
| PR3 | 2 | 1 | 0 | 3 |
| PR4 | 1 | 1 | 0 | 2 |
| PR5 | 1 | 1 | 0 | 2 |
| PR6 | 2 | 4 | 1 | 7 |
| PR7 | 4 | 9 | 4 | 17 |
| PR8 | 1 | 3 | 5 | 9 |
| Deferred | 0 | 1 | 0 | 1 |
| **Total** | **24** | **34** | **22** | **80** (logical ID count) |

> PR1 이 압도적으로 무거운 PR (35 task) — testFixture freeze + 8 interface bridge + Local backend + identity types 가 PR1 에 집중. PR2-5 는 backend 별 patterns 따라 가벼움 (2-4 task). PR7 (AOP) 가 두번째로 무거운 PR.

---

## 11. Step 3-R review gate (placeholder)

본 plan 은 Step 3 의 결과이며, Step 3-R 의 review gate 를 통해 implementation 시작 전 다음 reviewer 검증을 거친다:

| Reviewer | 검증 포커스 |
|----------|-------------|
| Architect | PR graph dependency 정합성 / testFixture freeze 시점 / AutoConfig 순서 |
| Silent-failure-hunter | 모든 task 의 `failureMode` interaction / runCatching ban / CE rethrow / blank-result throw path |
| Type-design-analyzer | nullable invariant pairing / sealed type 활용 / `LeaderSlot` value object boundary |
| Code-reviewer | bluetape4k-patterns conformance / KDoc / KLogging / requireXxx |
| Codex CLI | task graph 정합성 / 누락 acceptance criteria / 누락 dependency edge |

Step 3-R 의 P0=0 / P1≤2 도달 후 PR1 implementation 진입.

### 11.1 Step 3-R Appendix — iteration log (template)

다음 표는 Step 3-R 진행 시 채워질 예정:

| Round | Reviewer set | P0 | P1 | P2 | P3 | Action / commit |
|-------|--------------|----|----|----|----|----------------|
| 1 | architect + test-engineer + codex CLI (2026-05-13) | **3** (B1 PR1.5/B2 freeze surface/codex#1 T16↔T82 cycle) | 12 | 7 | 1 | applied (this commit) |
| 2 | (pending dispatch) | | | | | |

**Round 1 fixes 적용 (P0 + 핵심 P1)**:
- B1: PR1.5 sub-commit 메커니즘 제거 — PR1 자체에 freeze doc 포함, GitHub PR# reference 사용 (commit SHA 의존 없음). workspace branch protection 준수
- B2: T67 freeze surface 확장 — `LeaderElectorBridgeLog` + `LeaderRecorderContextDropLog` global API + `LeaderAopMetricsContext.Identified` constructor 추가
- codex #1: T16 ↔ T82 cycle 해소 — T82 를 T16 본문 내부 test case 로 통합 (별도 task file 아님), T82 depends-on T75 로 변경
- T82 acceptance: symmetric reset for `LeaderElectorBridgeLog` AND `LeaderRecorderContextDropLog` global (test-engineer)

**Round 1 deferred (Round 2 dispatch 후 평가)**:
- HIGH: PR1 split (PR1a/PR1b), TDD ordering reorder, PR7 T68b factory-probe specificity, PR2-6 CHANGELOG conflict
- test-engineer: T38 8th case, T78 dedicated test, T39 hash, T51 recursive, T52 rollback, T58 Java compat, MeterRegistry-absent test
- M1-M3: metric name SoT, KDoc per-task, count mismatch
- codex P2/P3: A0 grep extended, downstream verification, complexity recalc, README count

각 round 의 P0 (blocker) / P1 (high) 모두 fix 후 다음 round dispatch — Step 2-R 와 동일 convergence gate pattern.

---

## 12. Acceptance summary — full plan DoD

본 plan 의 모든 acceptance criteria 가 충족되면 issue #72 close 조건 만족:

- [ ] **PR1** merged + PR1.5 freeze commit (T67) develop 에 landed
- [ ] **PR2** merged (Lettuce + Redisson)
- [ ] **PR3** merged (Mongo)
- [ ] **PR4** merged (Hazelcast)
- [ ] **PR5** merged (ZooKeeper)
- [ ] **PR6** merged (Exposed JDBC + R2DBC)
- [ ] **PR7** merged (AOP + Spring autoconfig)
- [ ] **PR8** merged (cross-backend tests + docs/CHANGELOG)
- [ ] **T81 follow-up issue** 등록 (AUTO source LRU throttle redesign)
- [ ] Spec §15 의 36 acceptance criteria 모두 만족 (PR1-8 누적)
- [ ] Kover threshold: `leader-core` ≥ 80%, 모든 backend ≥ 60%
- [ ] `./gradlew clean build` (root) clean
- [ ] nightly profile 모든 backend matrix 통과
- [ ] README locale set 9 모듈 (root + 8 module) 동기화
- [ ] CHANGELOG `## [Unreleased]` → release headline (4 section) — issue #72 reference
- [ ] downstream consumer (`bluetape4k-workshop` 등) 의 deprecated `LeaderLease.leaderId` 사용 migration follow-up issue 등록 (workspace 외 scope)
- [ ] **Step 3-R review gate**: P0 = 0, P1 ≤ 2 (wording polish only)

---

## Appendix A — Task ID quick index

`T1 T2 T3 T4 T5 T6 T7 T8 T9 T10 T11 T12 T13 T14 T15 T16 T17` — PR1 Phase A+B+C base
`T18 T19 T20 T21` — PR2 Lettuce + Redisson
`T22 T23 T24` — PR3 Mongo
`T25 T26` — PR4 Hazelcast
`T27 T28` — PR5 ZK
`T29 T30 T31` — PR6 Exposed
`T32 T33 T34 T35 T36 T37` — PR7 AOP + autoconfig base
`T38 T39 T40 T41 T42 T43 T44 T45 T46` — PR8 tests + docs

Round 1 add: `T47 T48 T49 T50 T51 T52 T53 T54`
Round 2 add: `T55 T56 T57 T58 T59 T60 T61 T62`
Round 3 add: `T63 T64 T65 T66 T67`
Round 5 add: `T68 T68b T68c T69 T69b T70`
Round 7 add: `T71 T72 T73`
Round 7.6 add: `T74 T75 T76`
Round 8 add: `T77 T78 T79 T80`
Round 17 add: `T81 T82 T83 T84 T85`

Total 85 logical task ID (deferred T81 포함).

---

## Appendix B — File path quick index (변경 분포)

### `leader-core`
- `LeaderSlot.kt` (T1, NEW)
- `LeaderLease.kt` (T6, T57, MODIFY)
- `LeaderRunResult.kt` (T7, MODIFY)
- `LeaderLockHandle.kt` (T56, MODIFY)
- `coroutines/LeaderElectionInfo.kt` (T9, T65, T54, MODIFY)
- `identity/LeaderIdProvider.kt` (T2, NEW)
- `identity/RandomLeaderIdProvider.kt` (T3, NEW)
- `identity/HostnamePidLeaderIdProvider.kt` (T4, NEW)
- `identity/CompositeLeaderIdProvider.kt` (T5, NEW)
- `identity/LeaderIdSource.kt` (T47, NEW)
- `identity/LeaderIdResolutionException.kt` (T48, NEW)
- `identity/LeaderIdProviders.kt` (T49, NEW — `safeNextLeaderId`)
- `identity/LeaderInternalApi.kt` (T63, NEW)
- `identity/LeaderElectorBridgeLog.kt` (T75, T79, NEW)
- `metrics/LeaderAopMetricsContext.kt` (T76, NEW)
- `metrics/LeaderAopMetricsRecorder.kt` (T69, MODIFY)
- `metrics/LeaderRecorderContextDropLog.kt` (T74, NEW)
- `metrics/LeaderMetricNames.kt` (T66, MODIFY)
- 8 interface (T68) — `LeaderElector.kt`, `LeaderGroupElector.kt`, `AsyncLeaderElector.kt`, `AsyncLeaderGroupElector.kt`, `VirtualThreadLeaderElector.kt`, `VirtualThreadLeaderGroupElector.kt`, `coroutines/SuspendLeaderElector.kt`, `coroutines/SuspendLeaderGroupElector.kt`
- `local/LocalLeaderStateRegistry.kt` (T14, MODIFY)
- `local/AbstractLocalLeaderElector.kt` (T15, MODIFY)
- `local/AbstractLocalLeaderGroupElector.kt` (T15, MODIFY)
- `local/LocalLeaderElector.kt` (T15, MODIFY)
- `local/LocalLeaderGroupElector.kt` (T15, MODIFY)
- `local/LocalAsyncLeaderElector.kt` (T15, MODIFY)
- `local/LocalAsyncLeaderGroupElector.kt` (T15, MODIFY)
- `local/LocalVirtualThreadLeaderElector.kt` (T15, MODIFY)
- `local/LocalVirtualThreadLeaderGroupElector.kt` (T15, MODIFY)
- `coroutines/LocalSuspendLeaderElector.kt` (T15, MODIFY)
- `coroutines/LocalSuspendLeaderGroupElector.kt` (T15, MODIFY)
- `annotation/LeaderElection.kt` (T32, MODIFY)
- `annotation/LeaderGroupElection.kt` (T32, MODIFY)
- `spi/LeaderElectorFactory.kt` (T68b, MODIFY)
- testFixtures: `contract/AbstractLeaderIdContractTest.kt` + 3 variants (T16, NEW)

### `leader-redis-lettuce`
- `semaphore/LettuceSlotTokenGroup.kt` (T18, MODIFY)
- 4 elector (T19, MODIFY)

### `leader-redis-redisson`
- 4 elector (T20, MODIFY) + RMap audit

### `leader-mongodb`
- `lock/MongoLock.kt` (T22, MODIFY)
- 4 elector (T23, MODIFY)

### `leader-hazelcast`
- 4 elector (T25, MODIFY) + IMap audit

### `leader-zookeeper`
- 4 elector (T27, MODIFY) + znode JSON

### `leader-exposed-core`
- `tables/LeaderLockTable.kt` (T29, MODIFY)
- `tables/LeaderGroupLockTable.kt` (T29, MODIFY)

### `leader-exposed-jdbc`
- `ExposedJdbcSchemaInitializer.kt` (T64, MODIFY)
- 2 elector (T30, MODIFY)

### `leader-exposed-r2dbc`
- `ExposedR2dbcSchemaInitializer.kt` (T64, MODIFY)
- 2 elector (T30, MODIFY)

### `leader-micrometer`
- `MicrometerNames.kt` (T70-part1, MODIFY)
- `MicrometerLeaderAopMetricsRecorder.kt` (T70-part2, MODIFY)
- `LeaderBridgeMetrics.kt` (T70-part2, NEW)
- `autoconfigure/LeaderMicrometerAutoConfiguration.kt` (T70-part2, T84, MODIFY)

### `leader-spring-boot`
- `aop/spel/SpelExpressionEvaluator.kt` (T55, MODIFY)
- `aop/LeaderGroupElectionAspect.kt` (T34, T71, MODIFY)
- `aop/LeaderElectionAspect.kt` (T35, T71, MODIFY)
- `aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt` (T36, T59, MODIFY)
- `aop/properties/LeaderAopProperties.kt` (T33, MODIFY)
- `aop/autoconfigure/LeaderAopFactoryAutoConfiguration.kt` (T37, MODIFY)
- `aop/autoconfigure/LeaderAopAutoConfiguration.kt` (T68b, T69b, T73, T84, MODIFY)

### `buildSrc`
- `src/main/kotlin/detekt/SuperBridgeCallForbidRule.kt` (T83, NEW)

### Scripts / docs
- `scripts/leader-id-phase-a0-grep-gate.sh` (T50, NEW)
- `docs/superpowers/specs/2026-05-13-leader-id-testfixture-freeze.md` (T67, NEW + PR1.5 hash fill-in)
- `CHANGELOG.md` (PR1-PR8 누적)
- 모듈별 `README.md` + `README.ko.md` (PR2-PR8 각 PR 별 update)
- root `README.md` + `README.ko.md` (T44, PR8)

---

**End of Plan** — Step 3 deliverable. Implementation 진입 (PR1 first commit) 은 Step 3-R review gate P0=0 / P1≤2 충족 후.

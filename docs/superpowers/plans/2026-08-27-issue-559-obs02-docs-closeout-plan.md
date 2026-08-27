# Issue #559 OBS-02 Documentation Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 현재 `develop`에 병합된 OBS-02 lease-extension observation 계약을 EN/KO README와 미배포 manual 초안에 정확히 반영하고, Issue #559 PR을 merge-ready 상태로 만든다.

**Architecture:** production Kotlin 구현은 변경하지 않는다. `LeaderLeaseExtensionObservers`가 제공하는 framework-neutral event를 기준으로 core README와 manual draft를 작성하고, `MicrometerObservationLeaderLeaseExtensionObserver` 및 `LeaderObservationAutoConfiguration`의 실제 동작을 모듈 README에 연결한다. `0.5.0`으로 고정된 published manual은 보존하고 `docs/manual/drafts/`에만 새 API를 기록한다.

**Tech Stack:** Markdown, Kotlin API/source inspection, Gradle, `git diff --check`, repository manual validators, `bluetape-writer` SPW-01..05.

---

## Scope and evidence ledger

- Approved parent design: `docs/superpowers/specs/2026-08-17-issue-559-lease-extension-observation-design.md`.
- Earlier implementation plan: `docs/superpowers/plans/2026-08-17-issue-559-lease-extension-observation-implementation.md` (OBS-02 PR4 responsibility).
- Core source: `leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserver.kt`, `LockExtender.kt`, `LeaderLeaseAutoExtender.kt`.
- Micrometer source: `leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerObservationLeaderLeaseExtensionObserver.kt` and `LeaderObservationOptions.kt`.
- Spring source: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/metrics/LeaderObservationAutoConfiguration.kt` and `LeaseExtensionObservationRegistrationManager.kt`.
- Live issue authority: Issue #559 and Epic #699; target PR repository/base/head are `bluetape4k/bluetape4k-leader`, `develop`, `docs/issue-559-obs02`.
- Published manual constraint: `docs/manual/en/core/lease-extension.md`, `docs/manual/ko/core/lease-extension.md`, and `docs/manual/manifest.yaml` remain pinned to release `0.5.0` / commit `721a9a3808f67489d2bdb8177734325981c24977`.

## Task 1: Freeze the documentation contract

**Files:**

- Read: the source and artifacts listed in the scope ledger.
- Create: `.bluetape/issue-559-obs02-doc-contract.json` (workflow evidence only; ignored runtime state).

- [ ] **Step 1: Record source-to-claim mapping**

  Record these exact claims before editing:

  | Claim | Source evidence |
  | --- | --- |
  | USER and WATCHDOG source plus BLOCKING and SUSPEND execution are explicit enums | `LeaderLeaseExtensionObserver.kt`, `LeaderLeaseAutoExtender.kt` |
  | `Extended`, `Rejected`, `NotHeld`, `WrongThread`, and `BackendError` map to terminal events; callback errors do not alter lease results | `LeaderLeaseExtensionObserver.kt`, `LockExtender.kt`, `LeaderLeaseAutoExtender.kt` |
  | observer delivery is process-local, bounded (global 1024 / registration 256), non-blocking, and drops are counted | `LeaderLeaseExtensionObservers` constants and `droppedCount()` |
  | context redacts `lockName` and `auditLeaderId` from `toString`; event does not serialize them | `LeaderLeaseExtensionContext` and event tests |
  | Micrometer emits `bluetape4k.leader.lease.extension` with low-cardinality `source`, `execution`, `outcome`, `result`; lock/leader/exception details are opt-in | `MicrometerObservationLeaderLeaseExtensionObserver.kt`, `LeaderObservationOptions.kt` |
  | Spring registers one shared core observer per `ObservationRegistry`, skips NOOP registries, and closes context handles on destroy | `LeaderObservationAutoConfiguration.kt`, `LeaseExtensionObservationRegistrationManager.kt` |

  Expected evidence: the JSON records the claim list and the pinned-manual decision; no production source path is in the write scope.

## Task 2: Write the source-equivalent README updates

**Files:**

- Modify: `README.md`, `README.ko.md`.
- Modify: `leader-core/README.md`, `leader-core/README.ko.md`.
- Modify: `leader-micrometer/README.md`, `leader-micrometer/README.ko.md`.
- Modify: `leader-spring-boot/README.md`, `leader-spring-boot/README.ko.md`.
- Modify: `examples/prometheus-dashboard/README.md`, `examples/prometheus-dashboard/README.ko.md`.

- [ ] **Step 1: Add the core observer section**

  Add a short section adjacent to the existing core observability/LockExtender guidance. Include this minimal composition and explain that the returned handle removes only its registration:

  ```kotlin
  val registration = LeaderLeaseExtensionObservers.addObserver { event ->
      logger.info {
          "lease extension source=${event.source} execution=${event.execution} " +
              "outcome=${event.outcome::class.simpleName}"
      }
  }

  try {
      LockExtender.extendActiveLockDetailed(60.seconds)
  } finally {
      registration.close()
  }
  ```

  State that the callback receives an immutable terminal event, callback exceptions are isolated, `CancellationException` remains a cancellation, `Error` is not flattened, and bounded admission may increment `droppedCount()` without delaying the lease operation. Preserve exact API identifiers and use Korean technical prose in the `.ko.md` pair.

- [ ] **Step 2: Replace Micrometer's stale marker**

  Replace the current Issue #559 deferral paragraph with the actual observer composition:

  ```kotlin
  val observer = MicrometerObservationLeaderLeaseExtensionObserver(
      registry = observationRegistry,
      options = LeaderObservationOptions(),
  )
  val registration = LeaderLeaseExtensionObservers.addObserver(observer)
  ```

  Document observation name `bluetape4k.leader.lease.extension`, the four bounded low-cardinality tags, the `Extended`/`Rejected`/`NotHeld`/`WrongThread`/`BackendError` result mapping, and opt-in sanitised high-cardinality fields. Do not imply an OpenTelemetry bridge or export dependency.

- [ ] **Step 3: Replace Spring's stale marker**

  Explain that `LeaderObservationAutoConfiguration` supplies the core registration when a non-NOOP `ObservationRegistry` is available and tracing is enabled. Document the shared registry-identity/ref-count behavior, context destroy close, option-conflict fail-fast, and the independent `bluetape4k.leader.observability.enabled` / `.tracing.enabled` switches. Keep Actuator management security guidance separate from observation registration.

- [ ] **Step 4: Align the Prometheus dashboard example**

  State that the example's logging handler shows `leader.aop.*` and listener observations, while the current `supportsContext` predicate does not include the `bluetape4k.leader.lease.extension` name. Explain that the Spring bridge still emits lease-extension observations and an application-owned handler can opt into that exact name. Keep the example free of an OpenTelemetry SDK, exporter, or collector, and retain default lock/leader/exception detail redaction plus the existing localhost/authentication warnings.

- [ ] **Step 5: Review the root/core locale pairs**

  Explain the relationship between Issue #529 acquisition/execution observations and Issue #559 lease-extension terminal events without duplicating the module-specific configuration tables. Keep English and Korean headings, code tokens, links, numbers, and caveats aligned.

  Expected evidence: the exact stale-marker regex returns no output and every changed EN file has a corresponding KO change with the same API claims.

## Task 3: Add unreleased manual drafts and the lesson

**Files:**

- Create: `docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.en.md`.
- Create: `docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.ko.md`.
- Create: `docs/lessons/2026-08-27-issue-559-obs02-docs-closeout.md`.

- [ ] **Step 1: Draft the English manual**

  Use frontmatter `locale: en`, `status: unreleased`, `sourceReleaseRef: 0.5.0`, and `sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977`. Add an opening warning that the API is newer than the pinned manual. Cover prerequisites, core registration, event fields and outcome table, blocking/suspend/watchdog sources, cancellation and fail-open boundaries, bounded delivery/drop behavior, redaction, Micrometer mapping, Spring lifecycle/NOOP behavior, shutdown, diagnosis, and promotion conditions.

- [ ] **Step 2: Localize the Korean manual**

  Keep the same headings, code blocks, API identifiers, numbers, source commit, links, outcome names, and promotion gate. Apply the Korean naturalness checklist and use `관찰`, `lease 연장`, `보류`, `오류`, `등록`, and `수명주기` consistently; do not translate code tokens.

- [ ] **Step 3: Record the durable lesson**

  Record that the implementation train was already merged, the residual defect was stale reader guidance, and the pinned manual required an unreleased draft rather than a versioned-file edit. Include the source/readme scan, targeted test evidence, the failed `mutation-check` pre-init probe and its recovery by initializing a new receipt, and the future guard: every public observation contract must update both locale README surfaces and a release-scoped manual or explicit unreleased draft.

  Expected evidence: each new artifact has SPW-01, SPW-02, SPW-03, SPW-04, and SPW-05 recorded in the workflow evidence; no placeholder or unsupported release claim remains.

## Task 4: Validate and converge the documentation diff

**Files:**

- Read: all changed files and existing manual validation scripts.
- Create: `.bluetape/issue-559-obs02-doc-validation.json` (workflow evidence only).

- [ ] **Step 1: Run deterministic document checks**

  ```bash
  rg -n -i --glob 'README*' '(issue[[:space:]]*#559.*(deferred|tracked separately|out of scope|follow-up|별도로|미뤘|범위 밖|후속))|((deferred|tracked separately|out of scope|follow-up|별도로|미뤘|범위 밖|후속).*issue[[:space:]]*#559)' .
  git diff --check
  node /Users/debop/.codex/skills/bluetape-writer/scripts/audit-korean-terms.mjs leader-core/README.ko.md leader-micrometer/README.ko.md leader-spring-boot/README.ko.md examples/prometheus-dashboard/README.ko.md README.ko.md docs/manual/drafts/2026-08-27-issue-559-lease-extension-observation.ko.md docs/lessons/2026-08-27-issue-559-obs02-docs-closeout.md
  ```

  Expected: stale scan has no matches, `git diff --check` exits 0, and every terminology finding is repaired or recorded as a context-specific exception.

- [ ] **Step 2: Verify manual/source links**

  Run the repository manual validators that do not rewrite the pinned manifest, and inspect changed links against the current `develop` tree. Do not run a release-manifest update because the drafts are unreleased.

- [ ] **Step 3: Rerun proportional OBS-02 regression proof**

  ```bash
  ./gradlew :bluetape4k-leader-core:test --tests '*LeaderLeaseExtension*' --tests '*LockExtenderTest' --tests '*LeaderLeaseAutoExtender*' --rerun-tasks --no-daemon --no-configuration-cache --console=plain
  ./gradlew :bluetape4k-leader-micrometer:test --tests '*MicrometerObservationLeaderLeaseExtensionObserver*' --rerun-tasks --no-daemon --no-configuration-cache --console=plain
  ./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderObservationAutoConfigurationTest' --tests '*LeaseExtensionObservationRegistrationManagerTest' --rerun-tasks --no-daemon --no-configuration-cache --console=plain
  ```

  Expected: core, Micrometer, and Spring targeted suites pass with no source changes; any environment-only warning is recorded separately from product failures.

- [ ] **Step 4: Perform the final review read-back**

  Confirm that only approved README, draft, lesson, and plan paths changed; no production Kotlin, workflow, BOM, manual manifest, or example source changed. Record P0=0/P1=0 and the exact local HEAD before commit.

## Task 5: Commit and deliver the PR

- [ ] **Step 1: Create the Lore commit**

  Stage only the converged docs and evidence-backed lesson/plan artifacts. Use a Korean intent line followed by `Constraint:`, `Rejected:`, `Confidence:`, `Scope-risk:`, `Directive:`, `Tested:`, and `Not-tested:` trailers. Do not stage `.bluetape` runtime receipt files.

- [ ] **Step 2: Publish and create the authorized PR**

  Push `docs/issue-559-obs02` without force. Create the Korean PR against `develop`, assign `debop`, mirror Issue #559 milestone/labels, link `Closes #559`, and end the body with `## DoD Status` containing `Required checks: X/Y; N/A: N; Blocked: 0`.

- [ ] **Step 3: Read back live delivery**

  Query the PR head, base, metadata, checks, reviews, threads, and mergeability. Stop at merge-ready; CG-16 through CG-18 remain unchecked until a fresh approval tied to the exact PR head.

## Rollback and stop conditions

- If a README claim cannot be tied to current source, remove or qualify it before commit.
- If a pinned manual validator rejects the draft layout, repair only the draft or record the validator limitation; never change `docs/manual/manifest.yaml` as part of this closeout.
- If any targeted test fails, diagnose the source failure before claiming a docs-only result; do not accept a retry-only pass without evidence.
- If the branch diff contains production Kotlin or an unapproved path, stop and restore scope by editing only the approved worktree files; preserve all unrelated user changes.

## Plan self-review

- All OBS-02 acceptance claims map to current core, Micrometer, Spring, README, draft, lesson, and test evidence.
- The pinned-manual contradiction is resolved by an explicit unreleased-draft path.
- No placeholder commands, unknown files, new dependencies, module registration, workflow, diagram, release, or merge steps are introduced.

# Issue #795 MongoDB history indexer shutdown state checklist

## Context

- Work type: Type-C bug fix
- Repository: `bluetape4k/bluetape4k-leader`
- Base: `origin/develop` at `af25d87b8bc2d77df37c4b4648a7d16f859f6198`
- Head: `fix/issue-795-mongodb-indexer-shutdown-state`
- Issue: https://github.com/bluetape4k/bluetape4k-leader/issues/795
- Scope: `MongoLeaderHistoryIndexer` lifecycle state, gauge semantics, regression tests, module README parity, and a reusable lesson
- Stop condition: unresolved P0/P1, cancellation regression, non-terminal timeout state, stale exact-head evidence, or any broader public API decision

## Router and checklist contract

- [x] **WF-00 — Read guidance hierarchy**
  - **Action:** Read user, workspace, repository `AGENTS.md` files in order.
  - **Evidence:** `/Users/debop/.codex/AGENTS.md`, workspace guide, and repository guide were read before execution.
  - **Failure:** Stop classification if any governing file is unavailable.
- [x] **WF-01 — Classify Type-C**
  - **Action:** Bind the workflow type to the live defect and source evidence.
  - **Evidence:** Issue #795 reports a reproducible stale `building` state after bounded shutdown timeout.
  - **Failure:** Reclassify before mutation if scope becomes architectural or dependency-bearing.
- [x] **WF-02/WF-03 — Plan and approval**
  - **Action:** Present repository/base/head, RED-GREEN plan, validation, review, and PR stop boundary.
  - **Evidence:** The user approved #795 implementation and PR creation; merge is not authorized.
  - **Failure:** No source mutation or PR without approval.
- [x] **WF-04/WF-04A — Load execution contracts and initialize receipts**
  - **Action:** Load Type-C, Kotlin, testing, TDD, coroutine, worktree, writer, manifest, and topology contracts; initialize the guarded run.
  - **Evidence:** Run `20260829T124018Z-cf8752e3`, components `implementation`, `independent-review`, `pr-delivery`.
  - **Failure:** Stop if any required surface is missing or the receipt is invalid.
- [ ] **WF-05/WF-06 — Execute in dependency order and repair weak gates**
  - **Action:** Record RED, GREEN, static checks, review, push, PR, and CI in order; rerun affected downstream checks after repair.
  - **Evidence:** Pending final receipt and this checklist read-back.
  - **Failure:** Keep dependent rows unchecked.
- [x] **CL-01/CL-02 — Instantiate and classify before source mutation**
  - **Action:** Record every required, conditional, and N/A row before changing production or test source.
  - **Evidence:** This file precedes the RED test edit.
  - **Failure:** Reconstruct the checklist before further mutation.
- [x] **CL-03/CL-04/CL-05/CL-06 — Order, immediate evidence, fail-closed, repair**
  - **Action:** Attach fresh command/file/URL/count evidence immediately and do not bypass pending gates.
  - **Evidence:** RED → GREEN → module/Detekt/ABI → independent review 순서를 지켰고, 리뷰 P2 두 건을 RED/GREEN으로 수정한 뒤 모든 영향을 받은 검증과 final review를 다시 실행했다.
  - **Failure:** Reopen the first weak row and all dependent proofs.
- [ ] **CL-07/CL-08 — Refresh external hold and count completion**
  - **Action:** Refresh authority/head before push and PR; report required/N/A/blocked counts and unchecked IDs.
  - **Evidence:** Pending pre-PR and final DoD.
  - **Failure:** Do not create the PR or claim completion with stale or uncounted evidence.

## Common and Type-C gates

- [x] **CG-01/CG-02 — Refresh baseline and historical/live evidence**
  - **Action:** Re-read guidance, git state, GNO discovery, live issue metadata, and current source/tests.
  - **Evidence:** Clean worktree at exact base; Issue #795 OPEN, assignee `debop`, labels `bug/integration/test/maintenance`, milestone `1.0.0`; no existing PR.
  - **Failure:** Stop if live state contradicts scope or authority.
- [x] **CG-03/CG-04 — Protect user work and policy boundaries**
  - **Action:** Use a semantic isolated worktree; preserve canonical `.flow-inputs/`, other worktrees, and default branch.
  - **Evidence:** `.worktrees/fix/issue-795-mongodb-indexer-shutdown-state` at the exact base.
  - **Failure:** Do not overwrite or clean unrelated state.
- [x] **CG-05 — Reuse ecosystem patterns**
  - **Action:** Reuse project assertions, current coroutine scope, `withTimeoutOrNull`, and existing Micrometer gauge.
  - **Evidence:** Existing source/test anchors inspected; no new dependency or layer planned.
  - **Failure:** Narrow the design if a new dependency or abstraction appears.
- [x] **CG-06 — Prove public and documentation contracts**
  - **Action:** Keep `indexState: Int` compatible, add typed state additively, stabilize gauge codes, and align EN/KO README.
  - **Evidence:** `indexState: Int` remains compatible; `indexLifecycleState` is additive; EN/KO tables match source codes; final ABI gate reports `leader-mongodb` `No changes` and no unclassified incompatibilities.
  - **Failure:** Repair ABI/doc drift before review.
- [x] **CG-07 — RED/GREEN**
  - **Action:** First prove timeout remains `building`, then implement the smallest terminal-state transition and rerun targeted tests.
  - **Evidence:** Baseline 5 passed; RED failed with expected `Expected <0> to equal to <-2>`; final targeted run passed 6 tests.
  - **Failure:** A compile error or wrong failure is not valid RED.
- [x] **CG-08 — Serialize heavyweight checks**
  - **Action:** Run Gradle/module checks sequentially; do not parallelize shared build/container work.
  - **Evidence:** Baseline targeted test ran with `--max-workers=1`.
  - **Failure:** Rerun any raced heavy check sequentially.
- [x] **CG-09 — Record reusable lesson**
  - **Action:** Document why shutdown-timeout terminal state must win over delayed non-cancellable completion and index it in GNO.
  - **Evidence:** `docs/lessons/2026-08-29-issue-795-mongodb-indexer-shutdown-state.md`; temporary collection search returned the exact lesson URI, then the collection was removed.
  - **Failure:** Keep pre-PR proof open.
- [x] **CG-10 — Converge pre-PR proof**
  - **Action:** Complete targeted/module tests, Detekt, ABI, terminology audit, diff check, and independent P0/P1 review before commit.
  - **Evidence:** targeted 7/7, module 142/142(`failures=0`, `errors=0`, `skipped=0`), Detekt, ABI, terminology audit, `git diff --check` 통과; 독립 final review는 P0/P1/P2/P3 모두 0건으로 APPROVE했다.
  - **Failure:** Do not commit/push with unresolved P0/P1 or stale tests.
- [ ] **CG-11/CG-12/CG-12A — Authority, exact-head push, guidance refresh**
  - **Action:** Confirm approved repo/base/head, force-free push, remote SHA parity, and fresh guidance/template/issue metadata.
  - **Evidence:** Pending.
  - **Failure:** Stop PR creation on drift.
- [ ] **CG-13/CG-14/CG-15 — PR, exact-head CI/review, merge-ready report**
  - **Action:** Create a Korean PR that closes #795, mirrors metadata, ends with `## DoD Status`, and validate exact-head CI and threads.
  - **Evidence:** Pending PR URL, SHA, CI, and live read-back.
  - **Failure:** Repair metadata, CI, or findings before reporting merge-ready.
- [ ] **CG-16/CG-17/CG-18 — Fresh merge approval, merge, cleanup**
  - **Action:** Stop before merge; only continue after a separate exact-head approval.
  - **Evidence:** Intentionally pending; merge is not authorized.
  - **Failure:** Never enable auto-merge or clean branches/worktrees before approved merge proof.
- [x] **CG-X01 — Release/publish side effects**
  - **Action:** Determine whether release/tag/publish/dispatch is in scope.
  - **Evidence:** N/A; this task ends at PR/CI and performs no release action.
  - **Failure:** Open a separate Type-P workflow if scope expands.

- [x] **C-01/C-02 — Root cause and issue scope**
  - **Action:** Identify the first incorrect state transition and bind the surgical scope.
  - **Evidence:** `closeSuspend()` logs timeout without changing `_indexState`; delayed build completion can later overwrite a naive timeout marker.
  - **Failure:** Do not edit production code until deterministic RED exists.
- [x] **C-03 — Regression RED**
  - **Action:** Assert typed/numeric timeout state, delayed completion non-overwrite, duplicate close stability, and cancellation distinction.
  - **Evidence:** Runtime assertion RED: expected `-2`, actual `0`; compilation and fixtures succeeded.
  - **Failure:** Repair the test until it fails only because the feature is missing.
- [x] **C-04/C-05 — Surgical fix and GREEN blast radius**
  - **Action:** Add a stable state enum and atomic terminal transitions; run targeted and full module validation.
  - **Evidence:** Atomic state transition fix; targeted 7 passed; full module 142 tests across 17 XML suites with failures/errors/skipped all `0`; Detekt and ABI passed.
  - **Failure:** Fix production code, not the test, when GREEN fails.
- [ ] **C-06/C-07/C-08 — Lesson, authorized PR, merge readiness**
  - **Action:** Complete the lesson, CG-11 through CG-15, and phase-aware DoD.
  - **Evidence:** Pending.
  - **Failure:** Leave the workflow PENDING before merge approval.
- [ ] **C-09 — Post-approval closeout**
  - **Action:** Merge/sync/cleanup only after CG-16.
  - **Evidence:** Intentionally pending.
  - **Failure:** Stop at merge-ready.

## Kotlin, coroutine, and testing gates

- [x] **KT-FIN-01/02 — Current surface and validation contract**
  - **Action:** Re-read the final source/callers/tests and preserve existing API/error behavior.
  - **Evidence:** Final source/test/README diff preserves constructor and `indexState` contracts while adding typed state and `-2` metric meaning.
  - **Failure:** Reopen design on contract drift.
- [x] **KT-FIN-03/04 — Unsafe constructs and lifecycle ownership**
  - **Action:** Reject new `!!`, suspend `runCatching`, swallowed cancellation, non-atomic terminal transitions, or event-loop blocking.
  - **Evidence:** No new `!!`, suspend `runCatching`, forbidden exception assertions, or cancellation swallowing; terminal state uses one `AtomicReference` and caller cancellation test passes.
  - **Failure:** Remove the construct and rerun affected checks.
- [x] **KT-FIN-05 — Exposed boundary**
  - **Action:** Determine Exposed applicability.
  - **Evidence:** N/A; only `leader-mongodb` history indexer is touched.
  - **Failure:** Load Exposed patterns if scope expands.
- [x] **KT-FIN-06 — Triggered references**
  - **Action:** Apply Kotlin testing, cancellation, timeout, and fire-and-forget test references.
  - **Evidence:** Required skill/reference files were read before tests.
  - **Failure:** Stop Kotlin work on an unread required reference.
- [x] **KT-FIN-07/08/09/10/11 — Tests, docs, diagnostics, fresh validation, scope**
  - **Action:** Use intent-specific assertions, align EN/KO docs, remove diagnostics, rerun all checks, and converge the approved file set.
  - **Evidence:** Intent-specific assertions, EN/KO parity, Detekt success, targeted/module/ABI fresh runs, and `git diff --check` success; changed scope is the approved module/docs/checklist set.
  - **Failure:** Repair the first failing row before PR.
- [x] **KT-TEST-01/02 — Project idioms and cancellation semantics**
  - **Action:** Use JUnit 5, bluetape assertions, real cancellation, and deterministic coroutine control; never encode relational comparisons through `shouldBeTrue()`.
  - **Evidence:** JUnit 5 and `io.bluetape4k.assertions`; external timeout propagates `TimeoutCancellationException` while internal timeout records `SHUTDOWN_TIMEOUT`; targeted 6 passed.
  - **Failure:** Replace generic/forbidden assertions and flaky timing.
- [x] **KT-TEST-03/04 — Infrastructure and HTTP applicability**
  - **Action:** Determine Testcontainers and HTTP lifecycle applicability.
  - **Evidence:** N/A for the targeted indexer unit tests and HTTP; full module tests remain required.
  - **Failure:** Add the correct fixture/matrix if real backend or HTTP scope appears.
- [x] **KT-TEST-05 — Fresh targeted and module validation**
  - **Action:** Run targeted RED/GREEN, full module test, and parse JUnit XML for failures/errors/skips.
  - **Evidence:** Final targeted 7 passed; final full module 142 passed; XML totals `tests=142 failures=0 errors=0 skipped=0`.
  - **Failure:** Cached/retry-only/skipped evidence is insufficient.

## Writer gates

- [x] **SPW-01/SPW-02 — Audience, sources, and artifact contracts**
  - **Action:** Bind README and lesson readers to current source/issue evidence; include context, decision, outcome, verification, surprise, and future guard in the lesson.
  - **Evidence:** EN/KO README targets module users; lesson targets maintainers and includes context, decision, outcome, verification, surprise, and future guard grounded in Issue #795 and final source/tests.
  - **Failure:** Remove or qualify unsupported claims.
- [x] **SPW-03/KO-01..KO-07 — Korean technical register**
  - **Action:** Preserve identifiers and meaning, remove translationese/filler, verify terminology, and run the contextual audit for changed Korean files.
  - **Evidence:** Korean prose preserves identifiers and uses direct lifecycle terms; contextual terminology audit scanned 4 files with `findings=[]`.
  - **Failure:** Keep Korean artifacts blocked.
- [x] **SPW-04/SPW-05 — Traceability and read-back**
  - **Action:** Compare README/lesson claims with source/tests, then re-read rendered Markdown and record writer DoD.
  - **Evidence:** EN/KO state tables match enum/gauge codes; lesson claims match RED/GREEN evidence; rendered Markdown sections, tables, links, and line wrapping were read back.
  - **Failure:** Do not mark docs or lesson complete.

## Final count

- Required checks: pending final calculation
- N/A: release/publish, Exposed, targeted Testcontainers, HTTP, merge/cleanup before approval
- Blocked: 0
- Unchecked: all rows awaiting RED/GREEN, final validation, review, PR, and CI evidence

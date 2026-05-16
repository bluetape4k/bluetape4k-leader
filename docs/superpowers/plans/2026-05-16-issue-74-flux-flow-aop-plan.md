# Implementation Plan — Issue #74 `Flux` / `Flow` return support for leader AOP

## Scope

Implement the approved spec:

- Enable `Flux<T>` and Kotlin `Flow<T>` only for single-leader
  `@LeaderElection`.
- Add `@LeaderElection(streamBounded: Boolean = false)`.
- Allow stream methods when `autoExtend=true` or `streamBounded=true`.
- Keep `@LeaderGroupElection` `Flux<T>` / `Flow<T>` unsupported and prevent
  sync fallback at runtime.
- Hold the lock through stream completion, error, or cancellation.

Spec: `docs/superpowers/specs/2026-05-16-issue-74-flux-flow-aop-design.md`

## Task Plan

┌──────┬────────────┬────────────────────────────────────────────────────────────┬────────────────────────────────────────────────────────────┐
│ ID   │ Complexity │ Task                                                       │ Evidence / Acceptance                                      │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T1   │ Low        │ Add `streamBounded` to `LeaderElection` KDoc/API.          │ Existing annotation usages compile due default value.      │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T2   │ Medium     │ Extend `AdviceMetadata` with exact return-shape flags:     │ `isMono`, `isFlux`, `isFlow`, `isSuspend` are distinct.    │
│      │            │ Mono, Flux, Flow, suspend, and stream safety flag.         │ Branch routing can no longer confuse stream with sync.     │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T3   │ Medium     │ Add stream runtime rejection helpers before dispatch.       │ Invalid single stream config and all group streams produce │
│      │            │                                                            │ error streams, never sync fallback.                        │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T4   │ High       │ Add `LeaderElectionAspect` stream dispatch before sync.    │ `Flux` / `Flow` methods return cold stream wrappers.       │
│      │            │ Dispatch order: Flux, Flow, suspend, Mono, sync.           │ No backend call until subscription/collection.             │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T5   │ High       │ Implement `Flux` wrapper with `flux { ... }` and           │ Lock acquired once per subscription and held until         │
│      │            │ `Publisher.asFlow().collect { send(it) }` inside           │ complete/error/cancel.                                     │
│      │            │ `runIfLeaderResultSuspend`.                                │                                                            │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T6   │ High       │ Implement `Flow` wrapper with `channelFlow { ... }` and    │ No Flow context invariant violation; null values can pass  │
│      │            │ `.buffer(Channel.RENDEZVOUS)`.                             │ through `Flow<T?>`.                                        │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T7   │ Medium     │ Wire `LeaderAopMetricsRecorder` in stream lifecycle.       │ Attempt/acquired/started/finished/failed fire at stream    │
│      │            │                                                            │ subscription/collection lifecycle points.                  │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T8   │ High       │ Update `LeaderAnnotationValidatorBeanPostProcessor`.       │ Valid single stream configs pass; invalid stream configs   │
│      │            │                                                            │ fail/warn with precise messages.                           │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T9   │ Low        │ Add composed annotation support/check for `streamBounded`. │ `@AliasFor(annotation = LeaderElection::class,             │
│      │            │                                                            │ attribute = "streamBounded")` works in validator/aspect.   │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T10  │ Medium     │ Update `LeaderGroupElectionAspect` return detection for    │ `@LeaderGroupElection` Flux/Flow returns error streams at  │
│      │            │ unsupported streams.                                       │ subscription/collection time.                              │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T11  │ High       │ Add single-leader Flux tests.                              │ Success, skip-empty, fail-open, body error, backend error, │
│      │            │                                                            │ cancellation, multi-subscribe, autoExtend, metrics.        │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T12  │ High       │ Add single-leader Flow tests.                              │ Success, skip-empty, fail-open, body error, backend error, │
│      │            │                                                            │ cancellation, null element, invariant-safe collection.     │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T13  │ High       │ Add validator and composed-annotation tests.               │ strict=true/false matrix, both-true cell, group rejection, │
│      │            │                                                            │ and `@AliasFor(streamBounded)` covered.                    │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T14  │ Medium     │ Add group runtime rejection tests.                         │ Group Flux/Flow do not call `pjp.proceed()` and do not     │
│      │            │                                                            │ acquire a group slot.                                      │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T15  │ High       │ Update English KDoc, README locale set, and CHANGELOG.     │ Annotation/aspect KDoc, `README.md`, `README.ko.md`, and   │
│      │            │                                                            │ `CHANGELOG.md` describe behavior and runtime tightening.  │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T16  │ Low        │ Add lessons entry for stream AOP semantics.                │ Captures Flow context gotcha, channelFlow+rendezvous, and  │
│      │            │                                                            │ runtime stream rejection rule.                             │
├──────┼────────────┼────────────────────────────────────────────────────────────┼────────────────────────────────────────────────────────────┤
│ T17  │ High       │ Run targeted verification and review.                      │ Gradle tests/build, IDE diagnostics fallback, 6-tier code  │
│      │            │                                                            │ review, and PR-ready status.                               │
└──────┴────────────┴────────────────────────────────────────────────────────────┴────────────────────────────────────────────────────────────┘

## Implementation Details

### Return Shape Detection

Use constants for raw return type names:

- `reactor.core.publisher.Mono`
- `reactor.core.publisher.Flux`
- `kotlinx.coroutines.flow.Flow`

Do not use `Class.forName` for optional dependencies. `leader-spring-boot`
already has `kotlinx-coroutines-reactor` as `compileOnly`, and stream methods
are detected by return type name.

### Stream Safety Validation

For `@LeaderElection`:

```text
if return is Flux/Flow:
  allowed = ann.autoExtend || ann.streamBounded
  if !allowed -> invalid
```

For `@LeaderGroupElection`:

```text
if return is Flux/Flow:
  invalid
```

Runtime aspect rejection repeats this check before dispatch so non-strict
validator mode cannot permit sync fallback.

### Stream Wrappers

`Flux` branch:

- return `Flux.defer { flux { ... } }` or a cold `flux { ... }`;
- resolve metadata and lock name inside subscription;
- call `SuspendLeaderElector.runIfLeaderResultSuspend`;
- collect user `Flux` with `asFlow()` inside the guarded action;
- `send` each element downstream;
- map `Skipped` to empty completion.
- wire metrics inside the stream body: attempt at subscription, acquired after
  lock acquisition, taskStarted immediately before user stream collection,
  taskFinished on normal completion, taskFailed on body/backend/cancel.

`Flow` branch:

- return `channelFlow { ... }.buffer(Channel.RENDEZVOUS)`;
- resolve metadata and lock name inside collection;
- call `SuspendLeaderElector.runIfLeaderResultSuspend`;
- collect user `Flow` inside `withContext(LeaderElectionInfo(...))`;
- send each element downstream;
- map `Skipped` to empty completion.
- wire metrics at the same lifecycle points as the Flux branch.

Do not use `flow { withContext { emit(...) } }`.

### Metrics

Keep existing recorder API. Do not add `onTaskCancelled`.

- normal completion: `onTaskFinished`;
- body error, backend error, cancellation: `onTaskFailed`;
- cancellation rethrows `CancellationException`;
- downstream cancellation must not be converted into a Reactor/Flow error.
- `LeaderElectionInfo` and `LockHandleElement` must be visible inside guarded
  stream collection.

## Test Plan

### Unit Tests

Extend `LeaderElectionAspectSuspendMonoTest` or add
`LeaderElectionAspectStreamTest`:

- Flux elected success returns all elements.
- Flux skip returns empty stream and does not call `pjp.proceed()`.
- Flux fail-open contention executes body stream under `wasElected=false`.
- Flux backend error with `RETHROW` emits `LeaderElectionException`.
- Flux body error propagates body exception and releases lock.
- Flux cancellation releases lock and records failure.
- Flux `Disposable.dispose()` cancellation releases lock and records failure.
- Flux two subscriptions acquire twice and release twice.
- Flux `autoExtend=true` stream uses a real `LocalSuspendLeaderElector` with a
  short lease and verifies `state(lockName).leader.leaseUntil` advances while
  the stream is active.
- Flux empty-body / zero-emission stream still acquires and releases the lock.
- Flux slow downstream consumer does not leak or complete early.
- Flux `LeaderElectionInfo` and `LockAssert.assertLockedSuspend()` are visible
  inside guarded stream collection.
- Flow elected success returns all elements.
- Flow skip returns empty flow and does not call `pjp.proceed()`.
- Flow fail-open contention executes body stream under `wasElected=false`.
- Flow backend error with `RETHROW` throws `LeaderElectionException`.
- Flow body error propagates body exception and releases lock.
- Flow cancellation releases lock and records failure.
- Flow coroutine cancellation releases lock and records failure.
- Flow nullable element path supports `Flow<String?>`.
- Flow invariant regression: stream collection succeeds through
  `channelFlow` + `send`, not `flow` + context-shifted `emit`.
- Flow empty-body / zero-emission stream still acquires and releases the lock.
- Flow `flowOn` on the user flow does not break guarded collection.
- Flow slow collector keeps rendezvous/backpressure behavior and releases on
  cancel.
- Flow `LeaderElectionInfo` and `LockAssert.assertLockedSuspend()` are visible
  inside guarded stream collection.

Extend `LeaderGroupElectionAspectSuspendMonoTest`:

- Group Flux returns error stream and does not call body.
- Group Flow returns error stream and does not call body.

Extend `LeaderAnnotationValidatorBeanPostProcessorTest`:

- `@LeaderElection(autoExtend=true) fun flux(): Flux<T>` passes.
- `@LeaderElection(streamBounded=true) fun flux(): Flux<T>` passes.
- `@LeaderElection(autoExtend=true, streamBounded=true) fun flux(): Flux<T>` passes.
- `@LeaderElection fun flux(): Flux<T>` fails in strict mode and warns in
  non-strict mode.
- Same matrix for `Flow<T>`.
- `@LeaderGroupElection fun flux()/flow()` still fails in strict mode and warns
  in non-strict mode.
- A composed annotation can alias `streamBounded` with Spring `@AliasFor`.

### Verification Commands

```bash
./gradlew :leader-core:compileKotlin :leader-spring-boot:compileKotlin --no-configuration-cache --console=plain
./gradlew :leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.aop.LeaderElectionAspectStreamTest' --no-configuration-cache --console=plain
./gradlew :leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.aop.LeaderGroupElectionAspectSuspendMonoTest' --tests 'io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessorTest' --no-configuration-cache --console=plain
./gradlew :leader-spring-boot:build --no-configuration-cache --console=plain
```

If IDE diagnostics are unavailable, use compile/test output plus import scan as
fallback.

## Rollback / Compatibility

- `streamBounded` has a default value, so source usage remains compatible.
- Existing `Flux` / `Flow` methods that only warned in non-strict mode will now
  fail at runtime instead of executing with unsafe sync fallback. This is an
  intentional safety tightening and must be called out in README upgrade notes
  and `CHANGELOG.md`.
- `@LeaderGroupElection` stream support remains a follow-up; no group API field
  is added in this PR.

## Step 3 Checklist Completion Report

┌──────────────────────────────────────────────┬────────┬────────────────────────────────────────────────────────────┐
│ Item                                         │ Status │ Notes                                                      │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Plan maps every spec requirement             │ Done   │ Tasks T1-T17 cover API, runtime, validation, tests, docs. │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Task order is implementable                  │ Done   │ API/metadata before aspect wrappers, then tests/docs.      │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Targeted verification commands named         │ Done   │ Compile, targeted tests, and module build listed.          │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ README/KDoc/lesson tasks included            │ Done   │ T15-T16.                                                   │
└──────────────────────────────────────────────┴────────┴────────────────────────────────────────────────────────────┘

## Step 3-R Review Notes

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-74-flux-flow-aop-plan-20260516-115339.md`
Focused rerun: `.omx/artifacts/claude-issue-74-flux-flow-aop-plan-rerun-20260516-115712.md`

┌──────────┬────────────────────────────────────────────────────────────┬──────────┬────────────────────────────────────────────────────────────┐
│ Priority │ Finding                                                    │ Decision │ Follow-up                                                  │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P0       │ Runtime rejection helper was ordered after stream dispatch. │ Accepted │ T3 now precedes dispatch T4.                              │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P0       │ Metrics lifecycle wiring was missing as an implementation   │ Accepted │ T7 added and lifecycle points listed.                     │
│          │ task.                                                       │          │                                                            │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P1       │ `@AliasFor(streamBounded)` handling and test were missing.  │ Accepted │ T9/T13 added.                                              │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P1       │ `autoExtend` test lacked assertion mechanism.               │ Accepted │ T11 uses real local elector and `leaseUntil` advancement.  │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P1       │ Flux cancellation missed Reactor `Disposable.dispose()`.    │ Accepted │ T11 test list includes explicit dispose path.              │
└──────────┴────────────────────────────────────────────────────────────┴──────────┴────────────────────────────────────────────────────────────┘

### Step 3-R Integrated Findings

┌──────────┬───────┬───────┬────────────────────────────────────────────────────────────┐
│ Priority │ Count │ Open  │ Notes                                                      │
├──────────┼───────┼───────┼────────────────────────────────────────────────────────────┤
│ P0       │ 2     │ 0     │ Rejection ordering and metrics lifecycle fixed.            │
├──────────┼───────┼───────┼────────────────────────────────────────────────────────────┤
│ P1       │ 3     │ 0     │ AliasFor, autoExtend assertion, dispose cancellation fixed.│
├──────────┼───────┼───────┼────────────────────────────────────────────────────────────┤
│ P2/P3    │ 4     │ 0     │ Backpressure, docs, lesson, and rollback notes accepted.   │
└──────────┴───────┴───────┴────────────────────────────────────────────────────────────┘

Focused rerun result: P0 = 0, P1 = 0.

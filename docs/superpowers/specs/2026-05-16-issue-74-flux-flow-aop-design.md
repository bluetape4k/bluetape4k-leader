# Design Spec — Issue #74 `Flux` / `Flow` return support for leader AOP

## Context

Issue #74 closes the remaining asynchronous return-shape gap in the single
leader AOP surface. `@LeaderElection` and `@LeaderGroupElection` already
support:

- sync `T?` / `Unit`;
- Kotlin `suspend fun`;
- Reactor `Mono<T>`.

`Flux<T>` and Kotlin `Flow<T>` are currently blocked by
`LeaderAnnotationValidatorBeanPostProcessor` because returning a long-lived
stream while releasing the lock at method-return time can create split-brain.
This issue enables stream returns for `@LeaderElection` only. Group stream
support remains a follow-up because group-election options do not have automatic
lease renewal.

The key requirement is therefore not "allow these return types"; it is:

> acquire the leader lock per subscription/collection and release it only after
> stream completion, error, or cancellation.

## Current Evidence

- `LeaderElectionAspect` handles `Mono<T>` with `Mono.defer { mono { ... } }`,
  so lock acquisition happens per subscription.
- `LeaderElectionAspect` calls `SuspendLeaderElector.runIfLeader(...)` for
  suspend/Mono paths. Local suspend electors push `LockHandleElement`, start the
  single-leader watchdog when `autoExtend=true`, and release in a
  `NonCancellable` `finally` block.
- `LeaderGroupElectionAspect` has the same suspend/Mono shape but group options
  do not have `autoExtend`, so unbounded group streams cannot be made safe in
  issue #74 without adding a separate group lease-renewal design.
- `LeaderAnnotationValidatorBeanPostProcessor` currently treats `Flux` and
  `Flow` as unsupported. In non-strict mode it warns only, which can still let
  runtime execution fall through to the sync branch if the aspect does not
  explicitly recognize the return type.
- Reactor documentation recommends `doFinally` / `usingWhen` for cleanup on
  complete, error, and cancel. For this codebase, `kotlinx.coroutines.reactor.flux`
  is a better local fit because its coroutine is cancelled when the subscriber
  disposes.
- kotlinx-coroutines documentation and source confirm:
  - `flux { ... }` is cold, starts a coroutine per subscriber, and cancellation
    cancels that coroutine;
  - `Flow.asFlux()` creates a cold Flux and cancellation cancels the original
    Flow;
  - `Publisher.asFlow()` cancels the subscription in `finally`.

## Public API Decision

Add a bounded-stream opt-in to `@LeaderElection`:

```kotlin
annotation class LeaderElection(
    // existing properties...
    val streamBounded: Boolean = false,
)
```

### Meaning

`streamBounded=true` is a caller contract that the returned `Flux` / `Flow` is
finite enough to complete within the configured lease window, or that the stream
body performs explicit lease extension through `LockExtender`.

It is not a runtime timeout and it does not change release timing. Release timing
is always tied to stream termination:

- complete -> release lock and complete downstream;
- error -> release lock and propagate the error;
- cancel -> release lock and stop downstream emission.

### Validation Rules

For `@LeaderElection` methods returning `Flux<T>` or `Flow<T>`:

- allowed when `autoExtend=true`; intended for long-lived streams;
- allowed when `streamBounded=true`; intended for finite streams;
- invalid when both are false.

For `@LeaderGroupElection` methods returning `Flux<T>` or `Flow<T>`:

- still unsupported in issue #74;
- strict validation fails as before;
- non-strict validation may warn, but the aspect must still refuse runtime
  sync fallback by returning an error stream at subscription/collection time.

Future group auto-extension can relax this rule, but issue #74 should not imply
group stream safety.

Composed annotations may expose `streamBounded` with Spring `@AliasFor` the same
way they expose other `@LeaderElection` attributes.

## Runtime Semantics

### `Flux<T>`

Return a cold `Flux` that does no backend work until subscription:

```kotlin
flux {
    val result = elector.runIfLeaderResultSuspend(lockName) {
        withContext(LeaderElectionInfo(lockName, true)) {
            val upstream = pjp.proceed() as Flux<Any>
            upstream.asFlow().collect { send(it) }
        }
    }
    when (result) {
        is Elected -> Unit
        Skipped -> Unit // empty Flux
        is ActionFailed -> throw result.cause
    }
}
```

The action does not return the body `Flux`; it collects and re-emits it inside
the guarded scope. This is the essential split-brain mitigation.

### `Flow<T>`

Return a cold `Flow` that does no backend work until collection:

```kotlin
channelFlow {
    val result = elector.runIfLeaderResultSuspend(lockName) {
        withContext(LeaderElectionInfo(lockName, true)) {
            val upstream = pjp.proceed() as Flow<Any?>
            upstream.collect { send(it) }
        }
    }
    when (result) {
        is Elected -> Unit
        Skipped -> Unit // empty Flow
        is ActionFailed -> throw result.cause
    }
}.buffer(Channel.RENDEZVOUS)
```

`Flow<T?>` can emit null values. `Flux<T>` cannot emit null values by the
Reactive Streams contract; that remains Reactor's normal constraint.

Implementation must not use `flow { withContext { emit(...) } }`. Kotlin Flow
enforces context preservation for `emit`, and context-shifting emits can throw a
Flow invariant violation. Use `channelFlow { withContext(...) { upstream.collect { send(it) } } }`
and add `buffer(Channel.RENDEZVOUS)` unless tests prove a different buffer is
needed. This keeps the leader context in the guarded collection coroutine while
using channel sends for cross-context emission.

### Routing And Runtime Rejection

Aspect dispatch order must classify stream return types before the sync branch:

1. `Flux<T>` -> stream-reactive branch;
2. `Flow<T>` -> stream-coroutine branch;
3. `suspend fun` -> coroutine branch;
4. `Mono<T>` -> reactive branch;
5. sync fallback.

Invalid stream configurations must fail at subscription/collection time even
when the validator is configured with `strict=false` and only logged a warning.
The aspect must never let `Flux` / `Flow` fall through to the sync branch.

Runtime rejection mapping:

- `Flux<T>` -> `Flux.error(LeaderElectionException(...))` or
  `Flux.error(LeaderGroupElectionException(...))`;
- `Flow<T>` -> `flow { throw LeaderElectionException(...) }` or group
  equivalent.

### Backpressure And Dispatcher

The stream aspect does not add polling or eager buffering. For `Flux<T>`,
`kotlinx.coroutines.reactor.flux { send(...) }` suspends sends according to
downstream demand. For `Flow<T>`, use rendezvous buffering after `channelFlow`
unless implementation tests require a different bounded buffer.

The aspect should not pin a dispatcher. Caller-provided Reactor `subscribeOn` /
`publishOn` and Flow `flowOn` remain the scheduling controls. Blocking work in
stream bodies is still the caller's responsibility.

### Failure Modes

`RETHROW`:

- backend acquisition failure emits stream error (`LeaderElectionException`);
- body failure emits the body error.

`SKIP`:

- contention produces an empty stream;
- backend failure produces an empty stream after metrics record the backend
  failure.

`FAIL_OPEN_RUN`:

- contention or backend failure collects the user stream under
  `LeaderElectionInfo(wasElected=false)` and a fail-open `LockHandleElement`;
- downstream complete/error/cancel still bounds the fail-open scope.

## Metrics Semantics

Stream metrics map to stream lifecycle, not method-return lifecycle:

- `onLockAttempt`: at subscription/collection time;
- `onLockAcquired`: after the lock is acquired;
- `onTaskStarted`: immediately before collecting the user stream;
- `onTaskFinished`: only on normal stream completion;
- `onTaskFailed`: on body error, backend error, or cancellation.

Cancellation is treated consistently with existing suspend/Mono code: it is
recorded as task failure and rethrown as `CancellationException` so Reactor/Flow
cancellation remains cancellation, not a normal error signal.

## Non-Goals

- Native image support beyond existing Spring AOT coverage.
- Changing `Mono<T>` semantics.
- Supporting `Future`, `CompletableFuture`, `ListenableFuture`, or `Deferred`.
- Adding automatic lease renewal to group-election options.
- Supporting `Flux<T>` or `Flow<T>` on `@LeaderGroupElection`.
- Adding a hard stream timeout.

## Risks And Mitigations

### R1: Lock released before stream termination

Mitigation: AOP must collect/re-emit the stream inside `runIfLeaderResultSuspend`
instead of returning the user stream from inside the guarded action.

### R2: Non-strict validator warning still allows dangerous sync fallback

Mitigation: aspect return-type detection must explicitly route `Flux` and `Flow`
before the sync branch. Invalid stream shapes must return error streams at
subscription/collection time and must not fall through to sync execution.

### R3: Cancellation hides lock release

Mitigation: use coroutine `flux {}` / `channelFlow {}` builders and rely on
suspend elector `finally { withContext(NonCancellable) { release } }`. Add
cancellation tests.

### R4: Long stream without renewal

Mitigation: require `autoExtend=true` or explicit `streamBounded=true` for
single-leader streams. Keep group streams unsupported until a group renewal
design exists.

### R5: `LeaderElectionInfo` / `LockHandleElement` lost inside operators

Mitigation: document that suspend contexts are available inside the guarded
collection coroutine. Reactor non-suspend operators still cannot call suspend
`LockAssert`; users should use `flatMap { mono { ... } }` for suspend checks.

## Acceptance Criteria

- `@LeaderElection` supports `Flux<T>` and `Flow<T>` with per-subscription /
  per-collection locking.
- `@LeaderGroupElection` `Flux<T>` / `Flow<T>` remains unsupported and cannot
  fall through to sync execution.
- `LeaderAnnotationValidatorBeanPostProcessor` accepts valid stream
  configurations and rejects unsafe stream configurations.
- `LeaderAnnotationValidatorBeanPostProcessor` rejects `@LeaderElection`
  `Flux<T>` / `Flow<T>` when `autoExtend=false && streamBounded=false`.
- Contention maps to empty stream for `SKIP`, and fail-open maps to body stream
  execution with `wasElected=false`.
- Complete, error, and cancel release the lock.
- Multi-subscriber `Flux` acquires and releases the lock once per subscription.
- `autoExtend=true` stream tests prove at least one lease extension while the
  stream is active.
- KDoc is updated in English; `README.md` and existing localized README files
  such as `README.ko.md` describe stream contracts.
- Targeted `leader-spring-boot` tests cover Flux and Flow success, skip,
  fail-open, body error, backend error, and cancellation.

## Step 2-R Review Notes

### Local Multi-Perspective Review

┌─────────────┬──────────┬────────────────────────────────────────────────────────────┬──────────┐
│ Perspective │ Priority │ Finding                                                    │ Decision │
├─────────────┼──────────┼────────────────────────────────────────────────────────────┼──────────┤
│ Developer   │ P1       │ `Flow` emission inside `withContext` can violate Flow       │ Accepted │
│             │          │ context preservation.                                       │          │
├─────────────┼──────────┼────────────────────────────────────────────────────────────┼──────────┤
│ Ops/SRE     │ P1       │ Group streams lack auto-renewal and can split-brain.        │ Accepted │
├─────────────┼──────────┼────────────────────────────────────────────────────────────┼──────────┤
│ User/API    │ P1       │ strict=false validator warning must not permit sync         │ Accepted │
│             │          │ fallback for stream returns.                                │          │
├─────────────┼──────────┼────────────────────────────────────────────────────────────┼──────────┤
│ Security    │ P3       │ No new auth/secret boundary; keep SpEL handling unchanged. │ Accepted │
└─────────────┴──────────┴────────────────────────────────────────────────────────────┴──────────┘

### Claude Code Opus Advisor

Artifact: `.omx/artifacts/claude-issue-74-flux-flow-aop-spec-20260516-114545.md`
Focused rerun: `.omx/artifacts/claude-issue-74-flux-flow-aop-spec-rerun-20260516-114927.md`
Final focused rerun: `.omx/artifacts/claude-issue-74-flux-flow-aop-spec-final-rerun-20260516-115108.md`

┌──────────┬────────────────────────────────────────────────────────────┬──────────┬────────────────────────────────────────────────────────────┐
│ Priority │ Finding                                                    │ Decision │ Follow-up                                                  │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P0       │ `flow { withContext { emitAll(...) } }` violates Flow       │ Accepted │ Use `channelFlow` + `send` + rendezvous buffer in spec.    │
│          │ context preservation.                                      │          │                                                            │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P0       │ Group stream has no renewal path.                          │ Accepted │ Defer group `Flux` / `Flow`; runtime must reject streams.  │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P0       │ Validator non-strict mode can still allow sync fallback.    │ Accepted │ Add explicit stream dispatch and runtime rejection rule.   │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P1       │ Multi-subscriber, cancellation, autoExtend tests missing.  │ Accepted │ Added acceptance criteria; plan will include concrete      │
│          │                                                            │          │ tests.                                                     │
├──────────┼────────────────────────────────────────────────────────────┼──────────┼────────────────────────────────────────────────────────────┤
│ P2       │ Backpressure and dispatcher policy under-specified.        │ Accepted │ Added policy: no eager buffering, rendezvous Flow bridge,  │
│          │                                                            │          │ caller controls dispatcher.                                │
└──────────┴────────────────────────────────────────────────────────────┴──────────┴────────────────────────────────────────────────────────────┘

### Step 2-R Integrated Findings

┌──────────┬───────┬───────┬────────────────────────────────────────────────────────────┐
│ Priority │ Count │ Open  │ Notes                                                      │
├──────────┼───────┼───────┼────────────────────────────────────────────────────────────┤
│ P0       │ 3     │ 0     │ Flow invariant, group stream lease, sync fallback fixed in │
│          │       │       │ spec.                                                      │
├──────────┼───────┼───────┼────────────────────────────────────────────────────────────┤
│ P1       │ 4     │ 0     │ Test and runtime contract gaps accepted into spec/plan.    │
├──────────┼───────┼───────┼────────────────────────────────────────────────────────────┤
│ P2       │ 3     │ 0     │ Backpressure/dispatcher/AliasFor notes accepted.           │
├──────────┼───────┼───────┼────────────────────────────────────────────────────────────┤
│ P3       │ 1     │ 0     │ No user-blocking open question.                            │
└──────────┴───────┴───────┴────────────────────────────────────────────────────────────┘

Final focused rerun result after canonical Flow example correction: P0 = 0, P1 = 0.

## Step 2 / 2-R Checklist Completion Report

┌──────────────────────────────────────────────┬────────┬────────────────────────────────────────────────────────────┐
│ Item                                         │ Status │ Notes                                                      │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Draft spec written inside feature worktree   │ Done   │ This file is under the #74 worktree.                       │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Architecture options and API contract set    │ Done   │ Single-leader streams only; group streams deferred.        │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Step 2-R references loaded                   │ Done   │ `step-2r-spec-review.md` and Claude advisor reference.     │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Local multi-perspective review completed     │ Done   │ Developer, Ops/SRE, user/API, security perspectives.       │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Claude Code Opus advisor completed           │ Done   │ Initial + focused reruns saved under `.omx/artifacts/`.    │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ P0/P1 convergence verified                   │ Done   │ Final focused rerun reports P0 = 0, P1 = 0.                │
└──────────────────────────────────────────────┴────────┴────────────────────────────────────────────────────────────┘

## Step 1 / 1-R Checklist Completion Report

┌──────────────────────────────────────────────┬────────┬────────────────────────────────────────────────────────────┐
│ Item                                         │ Status │ Notes                                                      │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Target repository confirmed                 │ Done   │ `bluetape4k-leader`, branch `feat/issue-74-flux-flow-aop`. │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Worktree created from current develop        │ Done   │ `origin/develop@8ab6e30a`.                                │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ qmd / prior docs searched                    │ Done   │ Used `zsh -ic qmdq ...`; found AOP and lock extender docs. │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Current source inspected                     │ Done   │ AOP aspects, validator, annotations, suspend electors.     │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ Official docs / source evidence checked      │ Done   │ Reactor and kotlinx-coroutines docs plus local source jars. │
├──────────────────────────────────────────────┼────────┼────────────────────────────────────────────────────────────┤
│ User intent and boundary clear               │ Done   │ New feature/API addition; use full design workflow.        │
└──────────────────────────────────────────────┴────────┴────────────────────────────────────────────────────────────┘

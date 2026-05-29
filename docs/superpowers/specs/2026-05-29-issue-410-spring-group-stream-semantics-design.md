# Design Spec - Issue #410 Spring group stream semantics

## Context

`@LeaderElection` supports `Flux<T>` and Kotlin `Flow<T>` because single-leader streams can either opt into watchdog-style extension with `autoExtend=true` or declare a finite stream with `streamBounded=true`.

`@LeaderGroupElection` supports sync `T?`, `suspend T?`, and `Mono<T>`, but it does not expose group auto-extension. A group stream would need one acquired slot to stay valid until subscription cancellation, completion, or error, and the cleanup path must release that exact slot. Without a slot-scoped extension contract, allowing `Flux<T>` or `Flow<T>` would risk releasing before stream termination or holding an expired slot while downstream still observes elected execution.

## Decision

Group `Flux<T>` and Kotlin `Flow<T>` support is out of scope for 0.3.0.

The 0.3.0 public contract is:

- `@LeaderGroupElection` may protect sync `T?`, `suspend T?`, and Reactor `Mono<T>` methods.
- `@LeaderGroupElection` methods returning `Flux<T>` or `Flow<T>` are unsafe signatures.
- Strict startup validation must fail for group `Flux<T>` and group `Flow<T>`.
- Non-strict startup validation may warn, but runtime must still reject the stream at subscription or collection time.
- Runtime rejection must not call the method body, must not acquire a group slot, and must surface `LeaderGroupElectionException`.

## Lifecycle Semantics

For supported group return types:

- Sync: acquire one slot before body execution and release after the body returns or throws.
- Suspend: acquire one slot before coroutine body execution and release in the suspend elector cleanup path.
- Mono: defer acquisition until subscription and release after the `Mono` completes, errors, or is cancelled.

For unsupported group streams:

- `Flux<T>` returns a cold error `Flux`.
- `Flow<T>` returns a cold error `Flow`.
- Subscription or collection fails before `pjp.proceed()` is called.
- No fail-open branch is allowed for unsupported group streams, because fail-open would execute a stream without a lease contract while appearing to be under group election.

## Unsafe Signatures

These must continue to fail validation:

```kotlin
@LeaderGroupElection(name = "group-events", maxLeaders = 3)
fun groupEvents(): Flux<Event>

@LeaderGroupElection(name = "group-flow", maxLeaders = 3)
fun groupFlow(): Flow<Event>
```

`@LeaderElection` stream rules do not transfer to group election. In particular, `streamBounded` exists only on `@LeaderElection`, and `autoExtend` is not available on `@LeaderGroupElection` in 0.3.0.

## Future Implementation Shape

A future group stream feature must first add slot-scoped extension semantics:

- one acquired group slot per subscription or collection;
- watchdog extension tied to that slot identity;
- release of the same slot on complete, error, and cancellation;
- metrics that distinguish unsupported stream rejection, contention skip, backend failure, and body failure;
- tests for per-subscription acquisition, downstream cancellation, body error, backend error, fail-open policy, and slot cleanup.

Until that exists, group streams remain rejected even if validation is configured as non-strict.

## Step 2-R Local 7-Tier Review

| Tier | Verdict | Evidence |
| --- | --- | --- |
| 1 Security | PASS | Unsupported streams do not execute user code without a lease and introduce no new input surface. |
| 2 Ops/SRE | PASS | Contract prefers fail-fast startup/runtime errors over ambiguous long-lived slot ownership. |
| 3 Architecture | PASS | Keeps group election on existing slot model; avoids adding group auto-extension in 0.3.0. |
| 4 Kotlin/API | PASS | No new public annotation property; KDoc clarifies existing supported return shapes. |
| 5 Tests | PASS | Existing tests cover runtime rejection; this work adds group Flow validator coverage and no-body verification. |
| 6 Performance/Stability | PASS | Rejection is cold and performs no backend acquisition or stream collection. |
| 7 Docs/Release | PASS | README locale set and KDoc are updated to reflect the 0.3.0 contract. |

P0: 0. P1: 0. P2: 0. P3: 0.

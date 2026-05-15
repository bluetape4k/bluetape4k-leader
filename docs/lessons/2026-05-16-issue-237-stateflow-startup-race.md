# Issue 237 StateFlow Startup Race

## Context

`leaderStateFlow()` used `stateIn(SharingStarted.Eagerly)`, whose collector starts asynchronously. Hot event publishers with `replay = 0` could emit immediately after `leaderStateFlow()` returned and lose the event before the collector subscribed.

## Decision

For the default eager path, create a `MutableStateFlow` and launch upstream collection with `CoroutineStart.UNDISPATCHED`. Keep non-eager `SharingStarted` strategies on the existing `stateIn()` path.

## Outcome

The eager collector subscribes before `leaderStateFlow()` returns, while public API shape and cancellation behavior stay unchanged.
This relies on `MutableSharedFlow.collect()` registering its subscription synchronously before the first suspension point.
Do not apply the same undispatched-start pattern to custom `Flow` implementations unless their subscription registration has the same property.

## Verification

- Tests now use `MutableSharedFlow(replay = 0)` and no pre-subscription delay.
- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.coroutines.LeaderStateFlowExtTest' --no-configuration-cache --console=plain`
- Result: 10 tests passing, build successful.
- Post-PR Claude feedback added non-eager `SharingStarted` coverage, documented the `SharingStarted.Eagerly` singleton check, and captured the `MutableSharedFlow` subscription prerequisite.

## Future Notes

When testing hot-flow startup races, remove replay buffers and artificial subscription delays. Await state changes reactively with `first { ... }` instead of assuming immediate `StateFlow.value` updates after emit.

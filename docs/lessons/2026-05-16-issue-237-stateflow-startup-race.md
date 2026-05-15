# Issue 237 StateFlow Startup Race

## Context

`leaderStateFlow()` used `stateIn(SharingStarted.Eagerly)`, whose collector starts asynchronously. Hot event publishers with `replay = 0` could emit immediately after `leaderStateFlow()` returned and lose the event before the collector subscribed.

## Decision

For the default eager path, create a `MutableStateFlow` and launch upstream collection with `CoroutineStart.UNDISPATCHED`. Keep non-eager `SharingStarted` strategies on the existing `stateIn()` path.

## Outcome

The eager collector subscribes before `leaderStateFlow()` returns, while public API shape and cancellation behavior stay unchanged.

## Verification

- Tests now use `MutableSharedFlow(replay = 0)` and no pre-subscription delay.
- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.coroutines.LeaderStateFlowExtTest' --no-configuration-cache --console=plain`
- Result: 9 tests passing, build successful.
- Claude advisor review: no unresolved P0/P1 findings.

## Future Notes

When testing hot-flow startup races, remove replay buffers and artificial subscription delays. Await state changes reactively with `first { ... }` instead of assuming immediate `StateFlow.value` updates after emit.

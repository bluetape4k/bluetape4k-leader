# Issue 224 Listening Event Publisher

## Context

Blocking `ListeningLeaderElector` and `ListeningLeaderGroupElector` exposed callback listeners but not `LeaderElectionEventPublisher`, so coroutine consumers could not observe blocking election lifecycle events through `Flow`.

## Decision

Use `MutableSharedFlow(extraBufferCapacity = 64, onBufferOverflow = DROP_OLDEST)` inside blocking listener decorators. Emit with `tryEmit` so blocking and virtual-thread callers never suspend or need `runBlocking`.

## Outcome

Both blocking listener decorators now implement `LeaderElectionEventPublisher`. Sync and async paths emit `Elected`, `Revoked`, and `Skipped` events, including sync and async action failure revoke events.

## Verification

- IDE diagnostics: zero errors in touched Kotlin files.
- `./gradlew :leader-core:test --tests 'io.bluetape4k.leader.LeaderElectionListenerTest' --no-configuration-cache --console=plain`
- Result: 24 tests passing, build successful.
- Post-PR Claude feedback added sync action failure coverage, shared buffer capacity constant, and callback-vs-Flow ordering KDoc.

## Future Notes

When adding event publishers to blocking APIs, document buffer/drop semantics and cover async success, skip, and failure paths. Do not use suspend-only event helpers from blocking call paths.

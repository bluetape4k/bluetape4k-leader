# Issue 443 Framework-neutral Events

## Context

Milestone 0.3.0 issue #443 asked for a core observability API that does not
force Spring Boot, Ktor, Micrometer, or tracing-specific contracts.

## Decision

Keep `LeaderElectionEventPublisher.events` as the coroutine-native source of
truth and add explicit-scope callback registration methods on the same
publisher. Callback users must provide lifecycle-owned `CoroutineScope`; core
does not create a hidden global scope.

## Outcome

Spring/Ktor/Micrometer/logging/tracing adapters can now use the same core event
stream through `onEvent`, `onElected`, `onRevoked`, and `onSkipped` handles.

## Verification

- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderElectionEventTest' --tests 'io.bluetape4k.leader.LeaderElectionListenerTest' --no-daemon`
  passed with 38 event-focused tests.
- `./gradlew :bluetape4k-leader-core:test --no-daemon` passed with 703 tests.
- `git diff --check` passed.

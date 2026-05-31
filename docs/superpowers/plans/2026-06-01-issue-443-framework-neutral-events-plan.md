# Issue 443 Framework-neutral Leader Events Plan

## Implementation

1. Extend `LeaderElectionEventPublisher` with explicit-scope callback registration methods.
2. Add internal helper code that collects `events`, filters by event subtype, logs callback failures, and returns a cancellable `AutoCloseable`.
3. Make all `LeaderElectionEvent` payloads serializable with explicit serial version values.
4. Update `leader-core` README locale pair and top-level README locale pair with the event-publisher-first model.
5. Add a short lesson entry.

## Tests

1. Add unit tests for `onEvent` registration and close/unregister behavior.
2. Add unit tests for `onElected`, `onRevoked`, and `onSkipped` filtering.
3. Add a unit test proving a throwing callback does not stop the collector.
4. Add serialization tests for `Revoked` and `Skipped`.

## Verification

- `./gradlew :bluetape4k-leader-core:test --tests 'io.bluetape4k.leader.LeaderElectionEventTest' --tests 'io.bluetape4k.leader.LeaderElectionListenerTest' --no-daemon`
- `./gradlew :bluetape4k-leader-core:test --no-daemon`
- `git diff --check`

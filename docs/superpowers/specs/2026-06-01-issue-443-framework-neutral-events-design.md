# Issue 443 Framework-neutral Leader Events Design

## Context

Issue #443 asks for a framework-neutral observability API built around
`LeaderElectionEventPublisher`. Current code already has:

- hot `Flow<LeaderElectionEvent>` event streams in core listener-aware electors;
- `LeaderElectionListenerRegistry` callback registration;
- Spring observability adapters and a publisher-only fallback;
- Micrometer listeners and Ktor/Spring status endpoints as adapters.

The missing API is a callback registration surface on the event publisher itself
so Java and framework integration users do not have to collect `Flow` directly.

## Design

Add default methods to `LeaderElectionEventPublisher`:

- `onEvent(scope, listener)`
- `onElected(scope, listener)`
- `onRevoked(scope, listener)`
- `onSkipped(scope, listener)`

Each method accepts an explicit `CoroutineScope` and a Java `Consumer` callback,
then returns `AutoCloseable`. Closing the handle cancels the collection job.

Scope ownership stays explicit. The core module will not create a hidden global
scope for Java users, and adapters can bind callbacks to their own lifecycle.

Event delivery remains best-effort. Callback exceptions are logged and ignored
so one failing observability callback does not stop the publisher collection.

`LeaderElectionEvent` should be serializable as a sealed framework-neutral event
family. `Elected` already is serializable; `Revoked` and `Skipped` should also
declare explicit `serialVersionUID` values.

## Non-goals

- Do not replace `Flow<LeaderElectionEvent>`.
- Do not add Spring, Ktor, Micrometer, tracing, or logging dependencies to
  `leader-core`.
- Do not create a global coroutine scope or background executor in core.
- Do not promise durable event replay. Existing hot stream semantics remain.

## Acceptance Mapping

- Framework-neutral API: default callback methods live on `LeaderElectionEventPublisher`.
- Java-friendly registration: methods use `java.util.function.Consumer` and return `AutoCloseable`.
- Flow compatibility: `events` remains unchanged.
- Adapter model: README explains that Spring, Ktor, Micrometer, logging, tracing,
  and custom dashboards should adapt from the core publisher.
- Tests: cover callback registration, unregister handles, event filtering, and
  callback exception isolation.

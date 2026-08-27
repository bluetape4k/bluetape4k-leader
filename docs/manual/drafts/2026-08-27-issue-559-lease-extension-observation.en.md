---
title: "Lease-extension observation"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Lease-extension observation

> Unreleased draft for Issue #559 (OBS-02). The observer hook and its adapters
> are newer than the pinned `0.5.0` manual. Do not present this draft as part of
> that release until the release manifest and release commit are updated.

## What this adds

`leader-core` exposes one framework-neutral terminal event for explicit
`LockExtender` calls and `LeaderLeaseAutoExtender` watchdog ticks. The event is
diagnostic only: it does not acquire ownership, change a deadline, or decide
whether a watchdog retries or stops. The core registry is process-local, so
applications that need cross-process aggregation should connect an adapter to
their existing metrics or tracing system.

The delivery path has bounded in-flight admission. Publication acquires permits
without waiting, so a slow or saturated observer does not make the core wait for
a permit or callback. The current in-flight limits are 1,024 deliveries
globally and 256 per registration. Registration count and callback fan-out are
not bounded by this registry, so keep application registrations small and
callbacks short. A rejected delivery increments
`LeaderLeaseExtensionObservers.droppedCount()` and is not retried by the core.

## Prerequisites and source contract

- JDK 25 or newer, as required by the project toolchain.
- `io.github.bluetape4k.leader:bluetape4k-leader-core` for the core hook.
- `io.github.bluetape4k.leader:bluetape4k-leader-micrometer` for the
  Micrometer adapter.
- `io.github.bluetape4k.leader:bluetape4k-leader-spring-boot` only when Spring
  Boot lifecycle management is desired.

The implementation sources are [`LockExtender`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt),
[`LeaderLeaseAutoExtender`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt),
[`ExtendOutcome`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt), and
[`LeaderLeaseExtensionObserver`](https://github.com/bluetape4k/bluetape4k-leader/blob/develop/leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseExtensionObserver.kt).
This draft describes the current `develop` implementation; the pinned manual
remains unchanged.

## Minimal core registration

Register an observer only for the period in which the application needs the
diagnostic stream. Run an explicit extension call inside a matching active
user-owned scope created by `@LeaderElection`, `@LeaderGroupElection`, or a
direct elector body. Outside a scope, or when a named lock does not match the
active scope, it returns `NotHeld` and publishes an event with `context = null`.
A fail-open `NotHeld` event still carries its lock name in `context` with
`auditLeaderId = null`. `WATCHDOG` events come only from a single-leader
`autoExtend = true` path; group election slots disable group auto-extension.
The code below is a blocking example. In a suspend active scope, keep the same
registration but call `LockExtender.extendActiveLockDetailedSuspend(60.seconds)`
inside the suspend function instead. Always close the returned handle when the
owning component stops:

```kotlin
val registration = LeaderLeaseExtensionObservers.addObserver { event ->
    logger.info {
        "lease extension source=${event.source} execution=${event.execution} " +
            "outcome=${event.outcome::class.simpleName}"
    }
}

try {
    LockExtender.extendActiveLockDetailed(60.seconds)
} finally {
    registration.close()
}
```

`addObserver` returns an idempotent `AutoCloseable`. `close()` removes only that
registration. A callback that already passed admission may finish after close,
and the registry does not promise callback ordering or a drain barrier. A
callback `Exception` is isolated and logged at most once per warning interval;
it cannot change the extension result. The core does not catch or flatten
`CancellationException` or `Error` from an extension delegate; those paths are
re-thrown without publishing an event.

The short example observes one explicit `USER` attempt. Keep the registration
open for the full single-leader action or component lifetime when `WATCHDOG`
ticks are needed; group election slots accept explicit `LockExtender` calls but
do not emit `WATCHDOG` events because group auto-extension is disabled.

## Event contract

Every event represents one terminal extension attempt that reached publication.
The event is immutable and has the following fields:

| Field | Values and meaning |
|---|---|
| `source` | `USER` for `LockExtender`; `WATCHDOG` for `LeaderLeaseAutoExtender`. |
| `execution` | `BLOCKING` for the blocking delegate; `SUSPEND` for the suspend delegate. |
| `outcome` | One of the existing `ExtendOutcome` values below. |
| `elapsedNanos` | Non-negative caller-side delegate-call duration. It is `0` when the call returns without running a delegate, such as an outside-scope lookup or immediate queue admission rejection; a timed-out queued user command may still run later and can have a non-zero value. |
| `context` | Optional `LeaderLeaseExtensionContext` for a matching active user-owned scope; `null` for watchdog events, scope-free calls, and named-lock mismatches. A fail-open `NotHeld` event carries the lock name with `auditLeaderId = null`. |

`LeaderLeaseExtensionContext` contains `lockName` and optional `auditLeaderId`
for an in-process adapter. Its `toString()` is redacted. Treat both values as
sensitive and do not log or export them without an explicit sanitisation policy.

## Outcome semantics

- `Extended(observedExpireAt)` means the backend extended the lease and supplied
  the observed expiry. The user path records that deadline before publishing;
  the watchdog retains its existing retry/stop handling.
- `Rejected` means a watchdog admission reservation failed, a bounded operation
  queue was full, or a queued user operation timed out before its command
  completed. A timed-out queued command may still run later. It is a skip signal,
  not proof that no backend work will occur or that ownership was lost.
- `NotHeld` means that the current scope has no usable ownership, including an
  expired, taken-over, mismatched, or fail-open scope. Calling outside an active
  scope also returns this value.
- `WrongThread` reports a thread-bound backend called from the wrong thread.
- `BackendError(cause)` preserves the backend `Exception`. When an extension
  delegate throws an `Exception`, the blocking or suspend user call publishes
  the event and rethrows that exception; when the delegate returns an
  `ExtendOutcome.BackendError` value, that value is published and returned as
  the outcome. A watchdog applies its existing classifier and retry/stop policy
  after publication. The core does not redact `cause`; custom observers must
  sanitise it before logging or exporting.

Normal lock contention remains a result/skip state rather than an exception. A
callback must not turn an observation into a control-plane decision.

## Blocking, suspend, and watchdog parity

The blocking methods `extendActiveLockDetailed(...)` and the suspend methods
`extendActiveLockDetailedSuspend(...)` publish the same event shape. The
watchdog publishes `WATCHDOG` with `BLOCKING` or `SUSPEND` according to its
delegate. Watchdog events never carry a user ownership context. Both paths keep
the existing atomic ownership check, user-deadline protection, cancellation
propagation, and backend error classification. A delegate-thrown
`CancellationException` or `Error` is rethrown without publishing an event. A
watchdog delegate `RejectedExecutionException` is published as `BackendError`
and forces the watchdog to stop regardless of the configured classifier.

When no observer is registered, the core avoids event/context allocation and the
extension path behaves as before. Registering an observer therefore adds only
diagnostic delivery work; it does not make lease extension synchronous with an
observer or change fail-open behavior.

## Micrometer adapter

`MicrometerObservationLeaderLeaseExtensionObserver` converts each core event to
one short terminal Observation named
`bluetape4k.leader.lease.extension`:

```kotlin
val observer = MicrometerObservationLeaderLeaseExtensionObserver(
    registry = observationRegistry,
    options = LeaderObservationOptions(),
)
val registration = LeaderLeaseExtensionObservers.addObserver(observer)

try {
    LockExtender.extendActiveLockDetailed(60.seconds)
} finally {
    registration.close()
}
```

This Micrometer snippet uses the blocking detailed API. In a suspend active
scope, keep the registration but call `extendActiveLockDetailedSuspend(60.seconds)`
inside the suspend function.

The bounded low-cardinality tags are `source`, `execution`, `outcome`, and
`result`:

| `ExtendOutcome` | `outcome` | `result` |
|---|---|---|
| `Extended` | `extended` | `success` |
| `Rejected` | `rejected` | `skipped` |
| `NotHeld` | `not_held` | `skipped` |
| `WrongThread` | `wrong_thread` | `error` |
| `BackendError` | `backend_error` | `error` |

`elapsedNanos` is not a tag; the Observation itself remains the timing
boundary. `includeLockName` and `includeLeaderId` are disabled by default. If
enabled, the values are added as high-cardinality fields only after the
configured `LeaderMetricTagOptions` sanitiser runs. `includeExceptionDetails` is
also opt-in and attaches the original backend exception through
`Observation.error(...)` without applying the tag sanitiser. Keep its default
`false` unless the downstream observation or tracing backend is approved to
receive raw exception messages and stack traces. A NOOP `ObservationRegistry`
produces no Observation.
The module adds no OpenTelemetry SDK, tracing bridge, exporter, or collector.

## Spring Boot lifecycle

With `leader-micrometer`, a non-NOOP `ObservationRegistry`, and the default
`bluetape4k.leader.observability.tracing.enabled=true`,
`LeaderObservationAutoConfiguration` obtains a core registration after
ObservationRegistry post-processing. The top-level observability switch and
the tracing property can disable the registration; a NOOP registry is skipped.

`LeaseExtensionObservationRegistrationManager` shares one Micrometer observer
per `ObservationRegistry` identity. Each application context receives an
idempotent handle. The core registration is removed only when the last handle
closes. If the same registry is requested with different
`LeaderObservationOptions`, acquisition fails fast rather than duplicating the
callback or silently weakening redaction.

The Spring integration does not add `@EnableAspectJAutoProxy`, a new extension
API, or an exporter. It only owns lifecycle and option wiring around the core
observer.

## Privacy, shutdown, and diagnosis

Keep `lockName`, `auditLeaderId`, and backend exception details out of default
low-cardinality tags. If correlation is necessary, use a small static lock set
or an explicit `HASH`/`TRUNCATE` policy and document the data owner. Do not use
raw dynamic names for tenant, user, URL, or credential-like values.

Close an application-owned registration before discarding its ObservationRegistry
or lifecycle component. Closing does not wait for already-admitted callbacks;
the observer must therefore tolerate a short late-delivery window. Treat
`droppedCount()` as a cumulative process-local counter: capture a baseline at
the start of an incident or observation window and inspect the delta. A rising
delta means observer delivery saturation, not an extension `Rejected` outcome;
reduce callback work or registration/fan-out, or use application-side sampling
or aggregation. The Prometheus dashboard example does not expose this counter.
The core admission limits are fixed; do not add waiting to the lease operation
itself.

The core does not redact `BackendError.cause`. Custom observers must sanitise
exception messages and stack traces before logging or exporting them.

An Observation proves that the core attempted to publish a terminal result; it
does not prove that a remote exporter accepted or stored the observation. The
core hook has no durable queue, cross-process deduplication, or replay contract.

## Promotion gate

This file remains an unreleased draft while `docs/manual/manifest.yaml` points
to `0.5.0` at `721a9a3808f67489d2bdb8177734325981c24977`. Promotion requires a
new release commit, synchronized English/Korean manual content, regenerated
module inventory, and the core, Micrometer, and Spring targeted tests. README
links may point to this draft, but the pinned manual must not claim the new API
until that release gate is satisfied.

## Next learning step

Read the module README pairs for the shortest integration examples, then inspect
the lifecycle and bounded-admission tests before enabling high-cardinality
diagnostics in production.

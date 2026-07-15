# Issue #537 Spring route guard design

## Context

Issue #537 adds opt-in, read-only Spring MVC and WebFlux route guards for
endpoints that may be served only by the current leader. The request path must
never acquire, extend, or release a lease. It may only make a best-effort
authorization decision from the application's leader identity and the current
leader state snapshot.

`LeaderElector.state(lockName)` alone is insufficient for this decision. An
occupied state proves that some node is the leader, not that the current JVM is
the leader. The default route authority therefore compares the
`LeaderSlot.leaderId` used by the application with the state's
`LeaderLease.auditLeaderId`.

Applications with a different local-ownership model may provide a custom route
authority. The built-in and custom models are intentionally exclusive. Spring
must fail startup when an application mixes them instead of silently choosing
one.

## Source evidence

- `LeaderSlot` is the existing immutable `(lockName, leaderId)` identity value.
- Slot-aware backend implementations stamp `LeaderSlot.leaderId` into
  `LeaderLease.auditLeaderId` for audit traceability.
- `LeaderState` is explicitly a best-effort snapshot and does not provide an
  atomic execution guarantee.
- `LeaderElectionState.state(lockName)` is synchronous. WebFlux integration must
  not perform that potentially blocking call on an event-loop thread.
- `LeaderElectionState.state(lockName)` has a source-compatible empty fallback,
  so an explicit capability is required before its snapshot can authorize a route.
- `leader-spring-boot` already uses Spring Boot auto-configuration and explicit
  configuration metadata, so route guards should follow the same conventions.

## Goals

- Provide one shared route-authorization contract used by MVC and WebFlux.
- Make `LeaderSlot` state comparison the safe default.
- Allow exactly one explicitly selected custom authority implementation.
- Reject mixed, missing, or ambiguous authority configuration at startup.
- Reject requests before handler invocation when leadership is not established.
- Keep responses deterministic and free of leader identity or location data.
- Preserve Reactor and coroutine cancellation behavior.

## Non-goals

- Redirecting clients to the current leader or exposing leader location/identity
  metadata; tracked by #606.
- Acquiring or extending a lease for the lifetime of an HTTP request; tracked by
  #607.
- Making a state snapshot equivalent to an atomic distributed-lock guarantee.
- Adding authentication, authorization, backend enumeration, or a general policy
  engine.
- Combining built-in and custom authority results.

## Authority contract

The public authority API is synchronous and side-effect-free:

```kotlin
fun interface LeaderRouteAuthority {
    fun evaluate(slot: LeaderSlot): LeaderRouteDecision
}

sealed interface LeaderRouteDecision {
    data object Allowed : LeaderRouteDecision
    data object NotLeader : LeaderRouteDecision
    data object Unavailable : LeaderRouteDecision
}
```

`Allowed` means that the handler may be invoked. `NotLeader` means that the
authority completed normally but did not establish local ownership.
`Unavailable` means that ownership could not be determined safely. Adapters
must treat every result other than `Allowed` as fail-closed. A Java SPI
implementation that returns `null` is normalized to `Unavailable`.

The SPI must not expose `LeaderState`, `LeaderLease`, leader identity, backend
exceptions, or request objects to response rendering. A custom implementation
may consult application-specific state, but it must be bounded, side-effect-free,
and must not acquire, extend, or release leader leases.

## Authority modes and configuration exclusivity

Add route-guard properties under `bluetape4k.leader.route-guard`:

| Property | Default | Meaning |
|---|---:|---|
| `enabled` | `false` | Enables route-guard authority and MVC/WebFlux helper auto-configuration. |
| `authority-mode` | `STATE` | Selects the built-in `STATE` authority or an application-provided `CUSTOM` authority. |
| `elector-bean` | empty | Selects the `LeaderElector` used by `STATE` mode. Empty uses the unique or primary candidate. |
| `rejection-status` | `SERVICE_UNAVAILABLE` | Status returned for fail-closed route rejection. Only the documented safe status set is accepted. |

Authority modes are exclusive:

| Mode | Built-in authority | User `LeaderRouteAuthority` beans | Result |
|---|---|---:|---|
| `STATE` | Present | 0 | Valid default configuration. |
| `STATE` | Present | 1 or more | Startup failure: built-in and custom models are mixed. |
| `CUSTOM` | Absent | exactly 1 | Valid custom configuration. |
| `CUSTOM` | Absent | 0 | Startup failure: selected authority is missing. |
| `CUSTOM` | Absent | 2 or more | Startup failure: custom authority is ambiguous. |

These checks run only when `route-guard.enabled=true`; the disabled default must
not change startup behavior for existing applications. In `STATE` mode,
`elector-bean` selects an explicit `LeaderElector`. When it is empty, Spring's
unique/primary candidate rules apply. A missing or ambiguous elector fails route
guard configuration without activating the custom model.

The selected elector must declare `supportsAuditLeaderState=true`. The default
capability is false and decorators delegate it, so wrappers cannot turn an
unsupported empty-state elector into an apparently valid authority source.
Unsupported electors fail startup and require explicit `CUSTOM` mode. The
public `StateLeaderRouteAuthority` constructor enforces the same invariant so
manual construction cannot bypass auto-configuration validation.

`CUSTOM` mode prohibits a non-empty `elector-bean`. Supplying both means that the
application selected the custom decision model while also configuring the
built-in state model, so startup fails as mixed configuration.

The built-in authority must not use `@ConditionalOnMissingBean` as a silent
override mechanism. Auto-configuration selects the implementation from
`authority-mode`, then a startup validator enforces the table above before any
route can serve traffic.

Configuration failures use `LeaderRouteGuardConfigurationException` and stable,
searchable error codes:

- `LEADER_ROUTE_AUTHORITY_MIXED`
- `LEADER_ROUTE_AUTHORITY_MISSING`
- `LEADER_ROUTE_AUTHORITY_AMBIGUOUS`
- `LEADER_ROUTE_ELECTOR_MISSING`
- `LEADER_ROUTE_ELECTOR_AMBIGUOUS`
- `LEADER_ROUTE_ELECTOR_STATE_UNSUPPORTED`

The exception message may contain bean names needed to repair configuration but
must not include a leader ID, lock state, credential, or backend connection
detail.

## Built-in state authority

`StateLeaderRouteAuthority` depends on a `LeaderElector` and evaluates one
`LeaderSlot` as follows:

1. Call `state(slot.lockName)` exactly once.
2. Return `Allowed` only when the state is occupied and
   `state.leader.auditLeaderId == slot.leaderId`.
3. Return `NotLeader` for an empty state, a different audit leader ID, or missing
   audit identity.
4. Return `Unavailable` when state lookup throws or returns an internally
   inconsistent value that cannot be trusted.

The application must create a leader ID unique to one live process incarnation
and reuse the same `LeaderSlot` for election and route guarding inside that
process. Reusing a fixed node ID across restarts can match a stale lease left by
the previous process. A lock-name-only election bridge cannot prove that the
backend stamped the expected audit identity, so missing audit identity is always
denied.

The default authority never calls `runIfLeader`, `runAsyncIfLeader`, extension,
release, or watchdog APIs. This invariant is protected by interaction tests.

## MVC and WebFlux adapters

Both adapters depend only on `LeaderRouteAuthority`, a `LeaderSlot`, and the
bounded rejection policy.

- The MVC adapter evaluates the authority before invoking the handler. It invokes
  the handler exactly once only for `Allowed`.
- The WebFlux adapter defers evaluation until subscription and offloads the
  synchronous authority call from the event loop. It must not subscribe to the
  handler publisher after a rejected decision.
- Cancellation before evaluation or before handler subscription must not start
  handler work. Cancellation after handler subscription follows normal Reactor
  and coroutine propagation.
- Ordinary authority exceptions are normalized to `Unavailable`; cancellation
  and interruption are preserved, are not sent as a route rejection, and do not
  fall through to the protected handler.

The feature remains opt-in at the route-registration level. Auto-configuration
provides authority and helper factories, but it does not globally guard all
controllers or routes.

## Rejection policy

The default rejection status is `503 Service Unavailable`. Configuration accepts
only a small safe set intended for route availability semantics:

- `404 Not Found`
- `409 Conflict`
- `423 Locked`
- `503 Service Unavailable`

Any other value is a startup configuration error. MVC and WebFlux return the
same configured status and an empty response body. They do not emit a redirect,
leader ID, lock name, backend error, host name, or location header. The empty body
is deliberate: it keeps the first version transport-neutral and avoids creating
an identity-disclosure surface.

## Auto-configuration

Add a dedicated route-guard auto-configuration after leader election backend
selection. It is responsible for:

- binding `LeaderRouteGuardProperties`;
- remaining inactive by default unless `route-guard.enabled=true`;
- creating the internal built-in authority only in `STATE` mode after rejecting
  every user-provided authority bean;
- rejecting electors that do not declare audit-identity state capability;
- validating authority-mode/bean exclusivity;
- registering MVC helpers only when Spring MVC classes are present;
- registering WebFlux helpers only when Spring WebFlux classes are present;
- preserving applications that have neither web stack on the classpath.

The MVC and WebFlux APIs remain optional compile-time integrations. Adding one
web stack must not require or activate the other.

## Documentation and diagram

English and Korean documentation must:

- show a shared `LeaderSlot` used by election and the default route guard;
- require a process-incarnation-unique leader ID and document supported STATE
  backends plus the CUSTOM fallback;
- explain that the state decision is best-effort and fail-closed, not atomic;
- show `authority-mode=CUSTOM` with exactly one user authority bean;
- document every startup configuration error;
- state that custom authority code must be side-effect-free and must not acquire
  or extend a lease;
- explain the safe rejection statuses and identity-free response.

Update the existing Spring architecture SVG and 2x PNG pair to show the route
guard authority and the separate MVC/WebFlux adapters. The diagram must visibly
separate the built-in state authority from the custom SPI and must not imply that
both execute together.

## Test design

### Authority tests

- occupied state with matching audit ID returns `Allowed`;
- empty state, missing audit ID, and mismatched audit ID return `NotLeader`;
- state-read failure returns `Unavailable`;
- one state read occurs per decision;
- no acquire, extend, release, or watchdog API is invoked.

### Configuration tests

- default `STATE` mode creates only the built-in authority;
- disabled default creates no authority or web helper and does not validate
  unrelated elector/custom-authority beans;
- `STATE` plus any user authority fails with `LEADER_ROUTE_AUTHORITY_MIXED`;
- `STATE` selects an explicit `elector-bean`, otherwise a unique or primary
  `LeaderElector`;
- `STATE` with a missing or ambiguous elector fails with the matching stable
  configuration error code;
- `STATE` with an empty-fallback elector or unsupported wrapper fails with
  `LEADER_ROUTE_ELECTOR_STATE_UNSUPPORTED`;
- `CUSTOM` with exactly one user authority starts without the built-in authority;
- `CUSTOM` with no user authority fails with `LEADER_ROUTE_AUTHORITY_MISSING`;
- `CUSTOM` with multiple user authorities fails with
  `LEADER_ROUTE_AUTHORITY_AMBIGUOUS`;
- `CUSTOM` with a non-empty `elector-bean` fails with
  `LEADER_ROUTE_AUTHORITY_MIXED`;
- unsupported rejection statuses fail property validation;
- MVC-only, WebFlux-only, both-stack, and no-web-stack contexts remain isolated.

### Route contract tests

- MockMvc and WebTestClient leader requests invoke the handler exactly once;
- every fail-closed decision rejects before handler invocation;
- a Java authority returning `null` is rejected with the configured status;
- MVC and WebFlux return the same configured status and empty body;
- response headers and bodies contain no leader ID, lock name, or backend error;
- WebFlux evaluation does not run on the event-loop thread;
- Reactor cancellation and coroutine cancellation do not leak or invoke rejected
  handler work.

### Validation

- targeted authority, MVC, WebFlux, configuration, and AOT tests;
- complete `:bluetape4k-leader-spring-boot:test`;
- `:bluetape4k-leader-spring-boot:aotTest` and module build;
- `detekt` and the repository's applicable full validation gate;
- SVG/PNG pair validation and full-size visual review;
- six-perspective design/code review with zero open P0/P1 findings.

## Acceptance criteria

- Route guards are opt-in and read-only.
- The default authority starts only for an audit-state-capable elector and permits
  only a snapshot whose audit identity matches the guarded process-incarnation
  `LeaderSlot`.
- A custom authority is usable only in explicit `CUSTOM` mode and exactly one
  implementation is required.
- Disabled route guards do not alter existing application startup, including
  multi-backend applications.
- Built-in/custom mixing, missing custom authority, and ambiguous custom authority
  fail application startup with stable configuration error codes.
- Non-leader and unavailable decisions fail closed before handler execution.
- MVC and WebFlux preserve equivalent status, empty-body, and cancellation
  semantics.
- No passive guard path acquires, extends, or releases a lease.
- Public APIs have KDoc and English/Korean documentation.
- The Spring architecture SVG/PNG pair accurately shows the exclusive authority
  modes and the MVC/WebFlux adapters.

## Self-review

| Perspective | Result | Notes |
|---|---|---|
| Product scope | Pass | Keeps #537 read-only; redirect and request-bound lease work remain in #606/#607. |
| API design | Pass | One shared SPI and explicit decision model isolate both web adapters from backend details. |
| Configuration | Pass | Explicit modes and startup validation prevent silent override or ambiguous precedence. |
| Concurrency | Pass | Handler invocation and cancellation invariants are explicit; WebFlux offloads synchronous state reads. |
| Security | Pass | Fail-closed decisions, bounded statuses, empty responses, and identity-free errors reduce disclosure risk. |
| Operations | Pass | Stable startup error codes make invalid mode/bean combinations diagnosable. |
| Integration | Pass | Optional MVC/WebFlux activation and no-web-stack compatibility are included. |

Open P0 findings: 0. Open P1 findings: 0.

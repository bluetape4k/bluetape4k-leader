# Issue 537 Spring Route Guards Lessons

## Context

Spring applications needed an opt-in way to keep selected MVC and WebFlux
routes on the current leader without turning every HTTP request into a lock
acquisition. Redirect metadata and request-bound leases were deliberately split
into #606 and #607.

## Decision

- Make `STATE` the built-in default: read one state snapshot and compare its
  audit leader ID with the same process-incarnation `LeaderSlot` used for
  election.
- Expose `CUSTOM` as a separate application authority SPI. Never silently blend
  it with the built-in model; mixed configuration is a startup error.
- Keep rejections fail-closed, empty-body, identity-free, and limited to a small
  documented status set.
- Require an explicit audit-state capability before STATE can start, and enforce
  that invariant both in auto-configuration and the public authority
  constructor.

## Surprises and failures

An initial capability flag was too shallow. `ListeningLeaderElector` delegated
the flag and `state()`, but inherited slot bridge defaults for execution. Those
defaults converted `LeaderSlot` to `lockName` and discarded `leaderId`. A capable
backend wrapped with listeners could therefore pass startup, acquire under a
different audit identity, and be denied by its own route guard forever. The same
gap existed in Local synchronous elector's async slot path.

Java interop exposed a second fail-closed edge: a Java implementation of the
Kotlin authority interface can return `null`. Reactor's `Mono.fromCallable`
treats null as empty completion, so normalizing only exceptions could allow a
request to fall through without a decision.

Cancellation was another category boundary. Treating it as an ordinary
authority failure would hide caller shutdown and could leave work running after
the request was gone.

## Repair

- Add slot-aware listener overloads for sync, async, suspend, and result APIs;
  repair Local async delegation and preserve `Elected.leaderId`.
- Add real state round-trip tests that assert the audit identity while the slot
  is held, including every backend marked capable and its relevant decorators.
- Normalize Java `null` to `Unavailable` before MVC/WebFlux adaptation.
- Propagate cancellation, restore and rethrow interruption, and normalize only
  ordinary failures.
- Require leader IDs unique per live process incarnation; reuse the value only
  inside that process for election and route guarding.

## Outcome and proof

- Core: 713 tests passed.
- Consul: 64 tests passed; DynamoDB: 30 tests passed.
- Kubernetes: 13 unit and 21 K3s integration tests passed.
- Micrometer: 76 tests passed.
- Spring Boot: 422 tests and 6 AOT tests passed; module build passed.
- Diagram XML, connector, geometry, endpoint, mixed-corner, raster, and
  full-size visual checks passed.
- Two independent final reviews converged at P0=0, P1=0, P2=0.

## Future guard

A capability is not just a property. Every wrapper that advertises it must
preserve the identity-bearing operation that creates the observable state, and
tests must prove the round trip rather than only assert the flag. Treat
interface bridge defaults as compatibility fallbacks, not as evidence that a
decorator preserves semantic identity. At language and reactive boundaries,
test impossible-looking values such as Java null and classify cancellation
before ordinary failure normalization.

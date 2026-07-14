# Issues 531 and 536 Spring Operations Lessons

## Context

Milestone 0.5.0 needed two narrow Spring operations improvements: an opt-in readiness signal for lock names already known to the JVM and a first-party annotation composing Spring scheduling with the existing leader-election aspect. Backend-wide health, recent failure windows, YAML-only wrapping, and route helpers were split into #533, #602, #603, and #537 so the implementation could remain additive and reviewable.

## Decision

- Reuse `LeaderElectionStatusRegistry` and one read-only `LeaderElector.state` call per known lock instead of enumerating backend state or adding a cache.
- Keep readiness disabled by default and make status/details explicitly JVM-local diagnostics, never an ownership oracle.
- Implement `@LeaderScheduled` as a pure composition of Spring `@Scheduled` and core `@LeaderElection`; Spring remains the scheduler and the current aspect remains the election boundary.
- Dogfood the annotation on the existing history-retention job so normal main-source AspectJ compilation proves the real integration.

## Surprise and failure

The first pointcut expansion used a bare union of `@annotation(LeaderElection)` and `@annotation(LeaderScheduled)`. AspectJ then tried to apply the around advice to `LeaderScheduled` annotation static initialization and failed compilation because around advice is unsupported for that join point.

The readiness call-count test also initially failed after otherwise-correct health behavior because this repository uses a per-class JUnit lifecycle and MockK invocation history accumulated across test methods.

## Repair

- Constrain the pointcut union with `execution(* *(..))` so only method execution join points are advised.
- Clear the shared mock before each readiness test and verify each known lock is read exactly once, including the mixed success/failure case.
- Catch only `Exception` during backend state reads; do not use broad `runCatching` where it could absorb fatal errors.

## Outcome and proof

- Targeted readiness, auto-configuration, composed-annotation, aspect, and validator selection: 65 tests passed.
- Full `leader-spring-boot` suite: 372 tests passed.
- Module build: passed, including 5 AOT tests.
- Root Detekt command completed but reported `NO-SOURCE`; the result is recorded honestly rather than claimed as Kotlin source coverage.
- Final six-lens review converged at P0=0 and P1=0.

## Review misses and future guard

Treat a new AspectJ meta-annotation as a join-point-shape change, not just an annotation lookup change. Keep a main-source dogfood target and compile it before relying on unit tests that invoke advice directly. In repositories using JUnit `PER_CLASS`, exact-call tests must reset shared mocks before every test. For readiness contributors, always document and test the backend-call cardinality, disclosure surface, and the distinction between diagnostic health and lock ownership.

# Issue 74 Flux / Flow AOP

## Context

`@LeaderElection` already supported sync, suspend, and `Mono` methods, but `Flux` and Kotlin `Flow` were rejected because a stream can outlive the initial lease window.

## Decision

Support stream returns only for single-leader `@LeaderElection`, and require one of two explicit safety signals:

- `autoExtend = true` for long-running or unbounded streams.
- `streamBounded = true` for streams known to complete within the lease window.

Keep `@LeaderGroupElection` stream returns unsupported until group lease extension semantics are defined.

## Outcome

The aspect now wraps `Flux` and `Flow` lazily so the lock is acquired at subscription or collection time and released on completion, error, or cancellation. `Flow` uses `channelFlow` with rendezvous buffering to avoid Flow context-preservation violations.

The validator rejects unsafe stream signatures early, while runtime wrappers still fail at subscription or collection time when startup validation is disabled.

Aspect tests verify that `autoExtend=true` enables stream signatures and passes the option through the normal `LeaderElectionOptions` path. Actual watchdog lease advancement remains covered by core elector auto-extension tests rather than by constructing real electors inside Spring AOP unit tests.

## Verification

- `./gradlew :leader-spring-boot:compileTestKotlin --no-configuration-cache --console=plain`
- `./gradlew :leader-spring-boot:test --tests '*LeaderElectionAspectStreamTest*' --tests '*LeaderGroupElectionAspectSuspendMonoTest*' --tests '*LeaderAnnotationValidatorBeanPostProcessorTest*' --no-configuration-cache --console=plain`

## Future Guard

Do not test AOP stream `autoExtend` by constructing `LocalSuspendLeaderElector` directly in `leader-spring-boot` tests; the module's test runtime can expose coroutine binary drift unrelated to the aspect contract. Keep AOP tests focused on lazy acquisition, cancellation release, runtime validation, and validator policy, and cover actual lease extension in core elector contract tests.

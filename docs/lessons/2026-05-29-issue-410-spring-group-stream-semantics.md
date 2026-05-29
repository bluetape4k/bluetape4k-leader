# Issue 410 Spring Group Stream Semantics

## Context

Single-leader AOP supports `Flux` and Kotlin `Flow` by holding the lock for the subscription or collection lifecycle. Group election does not yet define slot-scoped auto-extension for long-lived streams.

## Decision

Keep `@LeaderGroupElection` `Flux<T>` and `Flow<T>` out of scope for 0.3.0. They must fail startup validation in strict mode and be rejected again by the aspect at subscription or collection time without calling the method body.

## Outcome

The public KDoc and README locale set now match the implementation: group AOP supports sync, suspend, and `Mono`, while group streams remain unsupported until per-slot extension semantics are designed.

## Verification

- `./gradlew :bluetape4k-leader-core:compileKotlin :bluetape4k-leader-spring-boot:compileKotlin --no-daemon` passed.
- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.aop.LeaderGroupElectionAspectSuspendMonoTest' --tests 'io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessorTest' --no-daemon` passed with 42 tests.
- `git diff --check` passed.

## Future Guard

Do not enable group `Flux` / `Flow` only by relaxing the validator. First design slot-scoped lease extension, cancellation cleanup, metrics, and fail-open semantics.

# Implementation Plan - Issue #410 Spring group stream semantics

## Scope

Define and publish the 0.3.0 contract for `@LeaderGroupElection` stream return types. This is a design and safety-documentation PR with small test/KDoc reinforcement, not a group stream implementation.

## Tasks

| ID | Task | Acceptance |
| --- | --- | --- |
| T1 | Write the design spec. | Spec states whether group `Flux` / `Flow` is in scope for 0.3.0 and defines lifecycle/rejection semantics. |
| T2 | Update public `LeaderGroupElection` KDoc. | KDoc says sync, suspend, and `Mono` are supported; group `Flux` / `Flow` are unsupported and rejected. |
| T3 | Update README locale set. | `README.md` and `README.ko.md` explain that group streams are out of scope for 0.3.0 and rejected twice. |
| T4 | Strengthen tests. | Validator covers group `Flow` startup failure; aspect tests verify unsupported group `Flux` / `Flow` do not call `pjp.proceed()`. |
| T5 | Add lesson entry. | Lesson records the contract and future implementation guard. |
| T6 | Verify targeted Spring Boot tests and diff hygiene. | `leader-spring-boot` targeted tests and `git diff --check` pass. |

## Verification Commands

```bash
./gradlew :bluetape4k-leader-core:compileKotlin :bluetape4k-leader-spring-boot:compileKotlin --no-daemon
./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.aop.LeaderGroupElectionAspectSuspendMonoTest' --tests 'io.bluetape4k.leader.spring.aop.validator.LeaderAnnotationValidatorBeanPostProcessorTest' --no-daemon
git diff --check
```

## Step 3-R Plan Review

| Tier | Verdict | Evidence |
| --- | --- | --- |
| 1 Security | PASS | Plan keeps unsupported stream body unexecuted and avoids fail-open stream execution. |
| 2 Ops/SRE | PASS | Rejection semantics are explicit and observable as startup/runtime failure. |
| 3 Architecture | PASS | No new group auto-extension API is introduced before slot semantics are designed. |
| 4 Kotlin/API | PASS | Public API remains source-compatible; KDoc correction matches existing implementation. |
| 5 Tests | PASS | Tests cover validator and runtime no-body behavior for both `Flux` and `Flow`. |
| 6 Performance/Stability | PASS | No backend acquisition on unsupported streams; no hot-path implementation change. |
| 7 Docs/Release | PASS | README locale set, spec, plan, and lesson are covered. |

P0: 0. P1: 0. P2: 0. P3: 0.

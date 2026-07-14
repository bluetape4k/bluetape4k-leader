# Issues 531 and 536 Spring Operations Implementation Plan

## Scope

Implement the approved, reduced 0.5.0 scope for #531 and #536. Keep #533, #537, #602, and #603 outside the code change.

The repository guidance mentions `codex run spec|plan --retry 3`, but the installed Codex CLI does not provide the `run` subcommand. These checked-in design and plan artifacts are the repository-local fallback; the implementation still receives a bounded `codex review` pass.

## Acceptance traceability

| Spec acceptance | Plan task | Proof |
|---|---|---|
| Readiness `UP` / `OUT_OF_SERVICE` / `DOWN`, empty, unknown expiry | Tasks 1-2 | deterministic clock unit tests |
| Opt-in bean and threshold binding | Tasks 1-2 | `ApplicationContextRunner` tests |
| One read per known lock; no exception disclosure | Tasks 1-2 | MockK call-count and health-detail assertions |
| Spring and leader aliases | Tasks 3-4 | merged-annotation tests |
| Existing AOP skip semantics and CTW pointcut | Tasks 3-4 | aspect test plus main-source history job dogfood compile |
| English/Korean caller guidance and security limits | Task 5 | locale diff review |
| Repository quality gates | Task 6 | module tests, Detekt, diff check, review |

## Task 1: #531 failing tests

Complexity: medium. Depends only on the approved readiness contract.

Files:

- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicatorTest.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionObservabilityAutoConfigurationTest.kt`

Add deterministic tests for status calculation, details, opt-in bean registration, threshold binding, negative thresholds, stable empty behavior, exactly one state read per known name, and absence of exception messages in details. Run the targeted tests and record the expected compile/test failure before production changes.

## Task 2: #531 minimal implementation

Complexity: medium. Depends on Task 1 RED evidence.

Files:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderObservabilityProperties.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthIndicator.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/observability/LeaderElectionReadinessHealthAutoConfiguration.kt`
- `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

Add health properties, the deterministic contributor, and a separate Actuator-guarded opt-in auto-configuration so the optional health API does not leak into the base observability configuration. Do not add dependencies or backend-specific branches. Rerun targeted tests to green.

Performance/stability guard: keep the algorithm at one sequential state read per snapshot name, with no cache, background worker, or shared mutable accumulator. Catch ordinary `Exception` only; do not absorb VM errors. Roll back by disabling the opt-in property and removing the contributor/import while leaving the existing status endpoint intact.

## Task 3: #536 failing tests

Complexity: medium. Depends on #531 GREEN so failures stay attributable.

Files:

- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduledTest.kt`
- `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspectTest.kt`

Test merged aliases for Spring scheduling and leader election. Add an aspect test that proves non-election skips the method body without throwing, and validator coverage for invalid SpEL/unsafe declarations through the composed annotation. Run the targeted tests and record RED before implementation.

## Task 4: #536 minimal implementation

Complexity: medium. Depends on Task 3 RED evidence.

Files:

- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/scheduling/LeaderScheduled.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElectionAspect.kt`
- `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/history/LeaderHistoryRetentionJob.kt`

Add the composed annotation and the smallest method-execution pointcut expansion required for AspectJ CTW. Reuse all current metadata resolution and advice branches, and migrate the blocking history-retention job as a main-source dogfood compile target. Rerun targeted tests to green. Roll back by restoring the two separate annotations and the original direct-annotation pointcut.

## Task 5: Unreleased documentation

Complexity: low. Depends on final public names from Tasks 2 and 4.

Files:

- `leader-spring-boot/README.md`
- `leader-spring-boot/README.ko.md`

Document opt-in readiness configuration, readiness-group inclusion, JVM-local limitations, linear backend-read cost, Actuator detail access, scheduling enablement, and `@LeaderScheduled` usage. Keep English/Korean aligned and do not change the release-pinned manual.

## Task 6: Verification and review

Complexity: medium. Depends on Tasks 1-5 GREEN and documentation parity.

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test --tests '*LeaderElectionReadinessHealthIndicatorTest' --tests '*LeaderElectionObservabilityAutoConfigurationTest' --tests '*LeaderScheduledTest' --tests '*LeaderElectionAspectTest' --no-configuration-cache --console=plain
./gradlew :bluetape4k-leader-spring-boot:test --no-configuration-cache --console=plain
./gradlew detekt --no-configuration-cache --console=plain
git diff --check
```

Then run a bounded Codex review against `develop`, fix HIGH/CRITICAL findings, rerun affected checks, and commit locally using the Lore commit protocol. Do not push, open a PR, merge, or close issues without separate authority.

## Risk prediction

| Risk | Signal | Mitigation / rerun point |
|---|---|---|
| Readiness probe backend amplification | State-call count exceeds known-lock count or dynamic registry is unbounded | One-call-per-name test; document bounded static readiness set; return to Tasks 1-2 if call count changes |
| Readiness details disclose operational identifiers | Exception text or raw names visible without operator awareness | Never include exception details; document Actuator detail access; return to Tasks 1-2/5 |
| Pointcut expansion breaks CTW or double-fires | AspectJ compiler errors, direct annotation regression, body count differs | Constrain pointcut to method execution, run aspect suite and main-source dogfood compile; return to Tasks 3-4 |
| Composed aliases drift from Spring/Core defaults | Merged annotation values differ | Cover every aliased attribute; return to Tasks 3-4 |
| Release manual claims unreleased APIs | Manual validation references APIs absent from pinned 0.4.0 commit | Leave `docs/manual` unchanged until manifest advances; use README/KDoc only |

## Plan review convergence

The same bounded external review attempts described in the spec timed out under repository startup hooks, so the main session completed all six plan lenses and the Step 3-R checklist.

| Priority | Area | Finding | Plan repair |
|---|---|---|---|
| P2 | Traceability | Acceptance criteria did not map one-to-one to tasks and proof. | Added acceptance traceability table. |
| P2 | Tests | Call count, exception disclosure, negative property, and composed validator paths were absent. | Added them to Tasks 1 and 3. |
| P2 | Auto-configuration | Optional Actuator type isolation and import ordering needed explicit ownership. | Assigned separate guarded auto-configuration and imports in Task 2. |
| P2 | Performance/stability | Linear backend cost, CTW dogfood, and rerun points were implicit. | Added task guards and risk-prediction table. |
| P2 | Docs/security | Actuator details and scheduling enablement were missing. | Assigned both locales in Task 5. |
| P2 | Rollback | No exact rollback path was recorded. | Added property-disable and dual-annotation rollback paths. |

Latest integrated result: P0=0, P1=0. All P2 findings are repaired in this revision.

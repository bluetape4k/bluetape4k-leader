# Issue 499 - leader-spring-boot Coverage

## Context

`leader-spring-boot` coverage looked like a 40% range regression, and a fresh
baseline measured the raw Kover XML at 29.53% line coverage.

## Decision

Count only production `main` source-set classes for this Spring Boot library
module. Exclude generated Spring AOT/TestContext bean-definition classes and
AspectJ synthetic closure classes from Kover reports because they are generated
instrumentation artifacts, not directly testable production behavior.

## Outcome

The module now reports 87.36% line coverage (`1327/1519`) and the governance
policy records `leader-spring-boot` as an 80% production-source target.
Small tests were added for blocking retention-job bean creation and observed
event publisher emission/registry registration.

## Verification

- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.history.LeaderHistoryRetentionAutoConfigurationTest' --tests 'io.bluetape4k.leader.spring.observability.LeaderElectionObservabilityAutoConfigurationTest' --no-daemon --no-configuration-cache --console=plain`
  - 9 tests passed.
- `./gradlew :bluetape4k-leader-spring-boot:cleanTest :bluetape4k-leader-spring-boot:test :bluetape4k-leader-spring-boot:koverXmlReport --no-daemon --no-configuration-cache --console=plain`
  - 335 tests passed.
  - `aotTest` 5 tests passed.
  - Kover XML: `LINE 1327/1519 = 87.36%`.
- `git diff --check`

## Future Guidance

Do not reintroduce hard Kover gates casually. This repo previously removed rigid
coverage gates because they blocked development on generated/integration-heavy
modules. Use production-source Kover XML evidence plus review policy unless the
user explicitly asks for a hard CI threshold.

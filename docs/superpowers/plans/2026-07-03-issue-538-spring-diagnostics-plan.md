# Issue #538 Spring Boot Diagnostics Implementation Plan

## 한국어 해설

이 문서는 `Issue #538 Spring Boot Diagnostics Implementation Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Spring Boot configuration metadata and startup diagnostics for `leader-spring-boot`.

**Architecture:** Add a nested diagnostics property object, a focused startup diagnostics auto-configuration, and a report/checker class that reads Spring beans and environment values without touching external leader backends. Keep warnings non-fatal by default and convert them to startup failure only when diagnostics strict mode is enabled.

**Tech Stack:** Kotlin 2.3, Spring Boot 4.1 auto-configuration, `ApplicationContextRunner`, JUnit 5, MockK where needed, bluetape4k assertions, manual Spring configuration metadata JSON, CairoSVG for diagram rendering if the architecture diagram changes.

---

## Files

- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderDiagnosticsProperties.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnostics.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnosticsAutoConfiguration.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnosticsException.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnosticsAutoConfigurationTest.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderProperties.kt`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `leader-spring-boot/README.md`
- Modify: `leader-spring-boot/README.ko.md`
- Modify if needed: `docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg`
- Modify if needed: `docs/images/readme-diagrams/leader-spring-boot-architecture-01.png`
- Create: `docs/lessons/2026-07-03-issue-538-spring-diagnostics.md`

## Task 1: Lock diagnostics properties and metadata tests

**Files:**
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnosticsAutoConfigurationTest.kt`
- Create: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`
- Modify: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderProperties.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderDiagnosticsProperties.kt`

- [ ] **Step 1: Add failing binding assertions**

Add tests that bind:

```kotlin
"bluetape4k.leader.diagnostics.enabled" to "false"
"bluetape4k.leader.diagnostics.strict" to "true"
"bluetape4k.leader.diagnostics.include-bean-names" to "false"
```

Expected assertions:

```kotlin
props.diagnostics.enabled shouldBeEqualTo false
props.diagnostics.strict shouldBeEqualTo true
props.diagnostics.includeBeanNames shouldBeEqualTo false
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test --tests "io.bluetape4k.leader.spring.LeaderPropertiesBindingTest" --no-configuration-cache --console=plain
```

Expected: FAIL because `LeaderProperties.diagnostics` is missing.

- [ ] **Step 3: Add diagnostics property model**

Create `LeaderDiagnosticsProperties`:

```kotlin
data class LeaderDiagnosticsProperties(
    val enabled: Boolean = true,
    val strict: Boolean = false,
    val includeBeanNames: Boolean = true,
) : Serializable
```

Add `@field:NestedConfigurationProperty val diagnostics: LeaderDiagnosticsProperties = LeaderDiagnosticsProperties()` to `LeaderProperties`.

- [ ] **Step 4: Run binding tests and verify GREEN**

Run the same focused Gradle command.

Expected: PASS.

- [ ] **Step 5: Add metadata test**

Add a JSON parsing test that asserts the metadata file contains these property names:

```text
bluetape4k.leader.diagnostics.enabled
bluetape4k.leader.diagnostics.strict
bluetape4k.leader.diagnostics.include-bean-names
bluetape4k.leader.mongo.single-collection
bluetape4k.leader.etcd.key-prefix
bluetape4k.leader.consul.key-prefix
bluetape4k.leader.dynamodb.table-name
management.endpoints.web.exposure.include
```

Expected before metadata edit: FAIL.

## Task 2: Implement startup diagnostics

**Files:**
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnostics.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnosticsAutoConfiguration.kt`
- Create: `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnosticsException.kt`
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/diagnostics/LeaderStartupDiagnosticsAutoConfigurationTest.kt`

- [ ] **Step 1: Write ApplicationContextRunner RED tests**

Cover:

```text
default context creates LeaderStartupDiagnostics
disabled property removes LeaderStartupDiagnostics
multiple non-local LeaderElector beans records warning and context still starts
strict mode fails when multiple non-local LeaderElector beans are present
management endpoint enabled without web exposure records warning
RAW lock-name metrics without allow-list records warning
```

- [ ] **Step 2: Implement report model and checker**

`LeaderStartupDiagnostics` should expose a `lastReport(): Report?` method for tests.
The report should contain active backend names, leader elector bean names, warning codes, and strict flag.

- [ ] **Step 3: Implement strict exception**

`LeaderStartupDiagnosticsException` should include warning codes in the message.

- [ ] **Step 4: Register auto-configuration**

Add `LeaderStartupDiagnosticsAutoConfiguration` after observability and actuator auto-configurations in `AutoConfiguration.imports`.

- [ ] **Step 5: Run focused diagnostics tests**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test --tests "io.bluetape4k.leader.spring.diagnostics.LeaderStartupDiagnosticsAutoConfigurationTest" --no-configuration-cache --console=plain
```

Expected: PASS.

## Task 3: Expand metadata and README

**Files:**
- Modify: `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json`
- Modify: `leader-spring-boot/README.md`
- Modify: `leader-spring-boot/README.ko.md`
- Test: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/metadata/LeaderConfigurationMetadataTest.kt`

- [ ] **Step 1: Extend metadata JSON**

Add backend root groups, backend property entries, diagnostics entries, and management web exposure metadata.

- [ ] **Step 2: Run metadata test**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:test --tests "io.bluetape4k.leader.spring.metadata.LeaderConfigurationMetadataTest" --no-configuration-cache --console=plain
```

Expected: PASS.

- [ ] **Step 3: Update README locale set**

Document diagnostics configuration, warning interpretation, strict mode, and management endpoint exposure in both README files.

- [ ] **Step 4: Validate README parity**

Run:

```bash
node scripts/check-readme-language-switches.mjs
git diff --check
```

Expected: PASS.

## Task 4: Diagram update if needed

**Files:**
- Modify if needed: `docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg`
- Modify if needed: `docs/images/readme-diagrams/leader-spring-boot-architecture-01.png`

- [ ] **Step 1: Decide whether the README needs diagram change**

If diagnostics is only a small configuration section and the existing architecture remains clear, record a no-diagram-change rationale in the final DoD. If the architecture diagram omits the new startup diagnostics path, update it.

- [ ] **Step 2: Render and inspect if changed**

Run:

```bash
~/.local/bin/cairosvg docs/images/readme-diagrams/leader-spring-boot-architecture-01.svg -o docs/images/readme-diagrams/leader-spring-boot-architecture-01.png -s 2
```

Then inspect the PNG at full size and run available SVG/geometry checks.

## Task 5: Verification, review, lesson, PR

**Files:**
- Create: `docs/lessons/2026-07-03-issue-538-spring-diagnostics.md`
- Create: `docs/review/2026-07-03-issue-538-spring-diagnostics-review.md`

- [ ] **Step 1: Run fresh targeted verification**

Run:

```bash
./gradlew :bluetape4k-leader-spring-boot:cleanTest :bluetape4k-leader-spring-boot:test --no-build-cache --no-configuration-cache --console=plain
```

Expected: PASS.

- [ ] **Step 2: Run full diff checks**

Run:

```bash
git diff --check
repo-status
```

Expected: PASS / clean except intended tracked changes.

- [ ] **Step 3: Run 7-Tier review**

Review security, SRE, structural impact, Kotlin code quality, tests, performance, and docs/release readiness. P0/P1 must be zero.

- [ ] **Step 4: Commit with Lore trailers**

Commit source, docs, tests, review, and lesson together.

- [ ] **Step 5: Open PR**

Open a PR against `develop`, assign `debop`, mirror issue milestone/labels, and ensure the final PR body section is `## DoD Status`.

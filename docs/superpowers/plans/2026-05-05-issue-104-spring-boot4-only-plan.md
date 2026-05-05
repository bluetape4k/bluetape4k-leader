# Implementation Plan — Issue #104: Drop Spring Boot 3, Merge Spring Boot Modules

**Date**: 2026-05-05
**Issue**: [#104](https://github.com/bluetape4k/bluetape4k-leader/issues/104)
**Spec**: [2026-05-05-issue-104-spring-boot4-only-design.md](../specs/2026-05-05-issue-104-spring-boot4-only-design.md)
**Status**: Ready for execution

---

## Overview

This plan implements the structural consolidation outlined in the spec:

1. Drop `leader-spring-boot3` entirely (Boot 3 is no longer supported).
2. Merge `leader-spring-boot-common` + `leader-spring-boot4` → `leader-spring-boot`.
3. Promote pure-Kotlin annotations + metrics SPI to `leader-core` so `leader-micrometer` and other backends can depend on them without pulling Spring transitively.
4. Rename `Boot4LeaderProperties` → `LeaderProperties` and the `boot4` package layer → `spring`.
5. Adopt **CTW-only** AspectJ weaving (Freefair post-compile weaving). Remove `@EnableAspectJAutoProxy` (incompatible with Spring Boot 4 AOT and produces double-advice when combined with CTW).
6. Update CI / nightly workflows, BOM, settings, CLAUDE.md.

### Critical risks (read before executing)

- **`@Around` pointcut strings are compile-time invisible.** Phase E requires updating the FQCN inside `@Around("@annotation(...)")` literal strings on `LeaderElectionAspect.kt` and `LeaderGroupElectionAspect.kt`. A miss here silently disables the aspect — runtime regression with no compile error.
- **`additional-spring-configuration-metadata.json`** also contains the old `LeaderAspectFailureMode` FQCN (string literal). Must be updated.
- **`@EnableAspectJAutoProxy` removal is mandatory.** Forgetting causes double-advice (lock acquired twice / metrics double-recorded) at runtime.
- **kotlin-bom must be imported AFTER `spring-boot-dependencies` BOM.** Project memory: spring-boot BOM forces kotlin 1.9.25, override required.
- **Sub-agent prompts**: when delegating, include the project rules block (Kotlin Edit Workflow, junit-platform.properties standard, CancellationException re-throw, Kover not Jacoco, etc.).

### Verification gates

- After each phase: run `ide_diagnostics` on changed `.kt` files; resolve imports + deprecations before moving on.
- Phase A & B: `./gradlew :leader-core:build` and `./gradlew :leader-micrometer:test` must pass.
- Phase J: full multi-module build + DoD grep must show 0 hits.

### Execution order rationale

A → B → C → D → E → F → G → H → I → J

- A first: `leader-core` is a leaf — extract SPI before any consumer reroutes its imports.
- B second: `leader-micrometer` is the only existing consumer that needs immediate fixup; doing it before deleting `leader-spring-boot-common` keeps the build green.
- C → D → E: build the new `leader-spring-boot` skeleton, then move common, then move boot4 (boot4 references common types so common moves first).
- **T37 (settings.gradle.kts)** must execute at the START of Phase C — before `./gradlew :leader-spring-boot:build` can discover the new module. See T12a.
- D 중간 compile checkpoint (T27a): Phase D 파일 이동 완료 후 `compileKotlin` 통과 확인. Phase E 시작 전 필수.
- F: tests after sources for parallelism with code reviewer.
- G/H: meta files (BOM, workflows, CLAUDE.md) — settings는 Phase C에서 먼저 처리.
- I last before J: only delete the old module directories once everything compiles against new packages, so we keep an escape hatch if D/E fail. **git tag pre-issue-104-delete 생성 후 실행.**
- J: full build + diagnostics + grep verification.

### File path conventions

All paths below are relative to the repo root: `/Users/debop/work/worktrees/bluetape4k-leader/issue-104/`. Absolute prefix is omitted for readability.

---

## Phase A — leader-core SPI extraction

**Goal**: Add 5 new files to `leader-core` (3 annotations + 2 metrics types) plus 5 unit-test files. No code outside `leader-core` is touched in this phase.

**Complexity**: low

### T1. Create `annotation/LeaderElection.kt` in leader-core
- **Complexity**: low
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderElection.kt` (new)
- **Action**: Copy from `leader-spring-boot-common/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderElection.kt`. Change `package` to `io.bluetape4k.leader.annotation`. Update KDoc references that mention old FQCN. Do not yet delete the original.
- **Verify**: file exists, compiles standalone (no imports from `org.springframework.*`).

### T2. Create `annotation/LeaderGroupElection.kt` in leader-core
- **Complexity**: low
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderGroupElection.kt` (new)
- **Action**: Copy from `leader-spring-boot-common/.../aop/LeaderGroupElection.kt`. Change package. Same KDoc cleanup.
- **Verify**: no Spring imports; `maxLeaders` default value preserved.

### T3. Create `annotation/LeaderAspectFailureMode.kt` in leader-core
- **Complexity**: low
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/annotation/LeaderAspectFailureMode.kt` (new)
- **Action**: Copy from `leader-spring-boot-common/.../aop/LeaderAspectFailureMode.kt`. Change package. Preserve enum constants (`INHERIT`, `RETHROW`, `SKIP` per spec §8.1).
- **Verify**: enum entries match spec exactly; KDoc retained.

### T4. Create `metrics/LeaderAopMetricsRecorder.kt` in leader-core
- **Complexity**: low
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/LeaderAopMetricsRecorder.kt` (new)
- **Action**: Copy interface + `NoOp` impl (if co-located) from `leader-spring-boot-common/.../aop/metrics/LeaderAopMetricsRecorder.kt`. Change package to `io.bluetape4k.leader.metrics`. Update internal references to `SkipReason` (same new package).
- **Verify**: no Spring or Micrometer imports; only Kotlin stdlib + leader-core internal types.

### T5. Create `metrics/SkipReason.kt` in leader-core
- **Complexity**: low
- **Files**:
  - `leader-core/src/main/kotlin/io/bluetape4k/leader/metrics/SkipReason.kt` (new)
- **Action**: Copy from `leader-spring-boot-common/.../aop/metrics/SkipReason.kt`. Change package to `io.bluetape4k.leader.metrics`.
- **Verify**: enum value list intact.

### T6. Add unit tests for new annotations + metrics SPI in leader-core
- **Complexity**: low
- **Files** (new):
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/annotation/LeaderElectionAnnotationTest.kt`
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/annotation/LeaderGroupElectionAnnotationTest.kt`
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/annotation/LeaderAspectFailureModeTest.kt`
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/metrics/SkipReasonTest.kt`
  - `leader-core/src/test/kotlin/io/bluetape4k/leader/metrics/NoOpLeaderAopMetricsRecorderTest.kt`
- **Action**: Implement per spec §8.1. Pattern: JUnit 5 + Kluent, `@TestInstance(PER_CLASS)`, backtick test names. Verify default attribute values, `@Retention(RUNTIME)`, `@Target(FUNCTION)`, enum completeness, NoOp callable without exception.
- **Verify**: each file compiles; no Spring deps in `leader-core/build.gradle.kts` are required.

### T7. Build verify leader-core
- **Complexity**: low
- **Files**: none (verification only)
- **Action**: Run `./gradlew :leader-core:build`. If any test fails, fix before moving on.
- **Verify**: BUILD SUCCESSFUL; all 5 new test classes pass; existing leader-core tests still green.

---

## Phase B — leader-micrometer dependency swap

**Goal**: Re-point `leader-micrometer` from `leader-spring-boot-common` (Spring-tainted) to `leader-core` (pure Kotlin).

**Complexity**: low

### T8. Swap `leader-micrometer` Gradle dependency
- **Complexity**: low
- **Files**:
  - `leader-micrometer/build.gradle.kts`
- **Action**: Change `api(project(":leader-spring-boot-common"))` → `api(project(":leader-core"))`. Spec §3.3.
- **Verify**: file diff matches spec.

### T9. Update imports in `MicrometerLeaderAopMetricsRecorder.kt`
- **Complexity**: low
- **Files**:
  - `leader-micrometer/src/main/kotlin/.../MicrometerLeaderAopMetricsRecorder.kt`
- **Action**: Replace `import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder` → `import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder`. Replace `import io.bluetape4k.leader.spring.aop.metrics.SkipReason` → `import io.bluetape4k.leader.metrics.SkipReason`.
- **Verify**: `ide_diagnostics` shows zero unresolved imports.

### T10. Update imports in `leader-micrometer` tests
- **Complexity**: low
- **Files**: every test under `leader-micrometer/src/test/kotlin/**/*.kt` referring to old SPI packages.
- **Action**: Same import rewrite as T9. Use `ide_search_text` for `io.bluetape4k.leader.spring.aop.metrics` to find all occurrences.
- **Verify**: `ide_diagnostics` clean across all test files.

### T11. Build verify leader-micrometer
- **Complexity**: low
- **Files**: none (verification only)
- **Action**: `./gradlew :leader-micrometer:test`.
- **Verify**: BUILD SUCCESSFUL.

---

## Phase C — leader-spring-boot module skeleton

**Goal**: Create the empty `leader-spring-boot` module with build script, resources, and directory layout per spec §4–§6. No source code is moved yet.

**Complexity**: medium

### T12a. ⚠️ settings.gradle.kts — 신규 모듈 추가만 (PREREQUISITE)
- **Complexity**: low
- **Files**: `settings.gradle.kts`
- **Action**: `include(":leader-spring-boot")` 한 줄만 추가. **구 3개 모듈(`leader-spring-boot-common`, `leader-spring-boot3`, `leader-spring-boot4`)은 아직 제거하지 않는다.** BOM이 구 모듈을 `project(...)` 참조하므로 settings에서 먼저 제거하면 Gradle 프로젝트 평가가 실패한다. 구 모듈 제거는 Phase I (T37_cleanup)에서 BOM과 동시에 처리한다.
- **Verify**: `./gradlew projects` 출력에 `:leader-spring-boot` 포함 확인. 구 모듈 3개 여전히 존재.

### T12b. ⚠️ leader-bom에 신규 모듈 제약 추가 (BOM atomicity)
- **Complexity**: low
- **Files**: `leader-bom/build.gradle.kts`
- **Action**: constraints 블록에 `api(project(":leader-spring-boot"))` 추가. 구 3개 모듈 제약은 아직 유지. 구 모듈 제약 제거는 Phase I (T37_cleanup)에서 settings 제거와 동시에 처리.
- **Verify**: `./gradlew :leader-bom:build` 성공; BOM에 `:leader-spring-boot` 포함.

### T12c. ⚠️ CTW 단독 동작 검증 (CRITICAL proof task)
- **Complexity**: medium
- **Files**: `leader-spring-boot4/src/main/kotlin/.../aop/autoconfigure/LeaderAopAutoConfiguration.kt` (임시 수정)
- **Action**: 기존 boot4 모듈에서 `@EnableAspectJAutoProxy`를 **임시로** 주석 처리한 뒤 `./gradlew :leader-spring-boot4:test`를 실행한다.
  - 테스트 통과 → CTW weaving이 Spring `@Bean` aspect와 정상 동작. `@EnableAspectJAutoProxy` 제거해도 안전.
  - 테스트 실패 → 현재 weaving은 Spring AOP 의존. 다음 해결책 중 하나 선택 후 진행:
    - **Option A (권장)**: `@Aspect`에 `@Component` 추가 + AutoConfig에서 `@Bean` 직접 등록 제거 → CTW가 Spring-managed singleton 사용
    - **Option B**: `@Configuration(proxyBeanMethods = false)` + `@SpringBeanAutowiredFields` / `@Configurable` 방식
  - 결과를 plan 노트에 기록 후 Phase E T31 전에 전략 확정.
- **Verify**: 테스트 pass/fail 결과 + aspect advice 실제 실행 여부 log 확인 (`DEBUG io.bluetape4k.leader.spring.aop.LeaderElectionAspect`). 검증 후 `@EnableAspectJAutoProxy` 주석 원복.

### T12. Create directory structure for leader-spring-boot
- **Complexity**: low
- **Files** (new directories):
  - `leader-spring-boot/`
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/`
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/{adapter,aop,backend,metrics,properties}/`
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/{autoconfigure,cache,properties,spel,util,validator}/`
  - `leader-spring-boot/src/main/resources/META-INF/spring/`
  - `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/`
  - `leader-spring-boot/src/test/resources/`
- **Action**: Create directories. No `.kt` files yet.
- **Verify**: `ls leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/` shows the 6 sub-dirs.

### T13. Write `leader-spring-boot/build.gradle.kts`
- **Complexity**: medium
- **Files**:
  - `leader-spring-boot/build.gradle.kts` (new)
- **Action**: Copy verbatim from spec §5. Confirm:
  - `id("io.freefair.aspectj.post-compile-weaving") version "9.5.0"` present.
  - `dependencyManagement.imports` order: `spring-boot4-dependencies` THEN `kotlin-bom` (project memory: feedback_spring_boot_kotlin_bom_override).
  - `kover.reports.verify.rule.bound.minValue = 60` (project memory: feedback_kover_unit_test_only_threshold).
  - `tasks.compileJava { inputs.files(tasks.processResources) }` retained for resources-before-aspectj-weave ordering.
- **Verify**: `./gradlew projects` lists `leader-spring-boot` after settings update (T31).

### T14. Write `AutoConfiguration.imports`
- **Complexity**: low
- **Files**:
  - `leader-spring-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (new)
- **Action**: Write the 5 lines from spec §6 (LeaderElectionAutoConfiguration, LocalLeaderConfiguration, LeaderAopFactoryAutoConfiguration, LeaderMicrometerAutoConfiguration, LeaderAopAutoConfiguration). Order matters per spec §3.3.
- **Verify**: file content exactly matches spec §6.

### T15. Write `additional-spring-configuration-metadata.json`
- **Complexity**: low
- **Files**:
  - `leader-spring-boot/src/main/resources/META-INF/spring/additional-spring-configuration-metadata.json` (new)
- **Action**: Copy from `leader-spring-boot4/.../additional-spring-configuration-metadata.json`. Replace any `"type": "io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode"` → `"type": "io.bluetape4k.leader.annotation.LeaderAspectFailureMode"`. Spec §2.2 import-update table.
- **Verify**: `rg "leader\.spring\.aop\.LeaderAspectFailureMode" leader-spring-boot/src/main/resources/` returns 0.

### T16. Write `junit-platform.properties` for leader-spring-boot tests
- **Complexity**: low
- **Files**:
  - `leader-spring-boot/src/test/resources/junit-platform.properties` (new)
- **Action**: Copy spec §8.5 content (PER_CLASS, parallel.enabled=false). Project memory: feedback_junit_platform_per_class — required for every new module.
- **Verify**: contents match spec exactly.

### T17. Write `logback-test.xml`
- **Complexity**: low
- **Files**:
  - `leader-spring-boot/src/test/resources/logback-test.xml` (new)
- **Action**: Copy from `leader-spring-boot4/src/test/resources/logback-test.xml`.
- **Verify**: byte-equivalent or only whitespace difference.

---

## Phase D — Move sources from leader-spring-boot-common

**Goal**: Migrate the surviving files from `leader-spring-boot-common` into `leader-spring-boot` with corrected imports. Deleted-class list per spec §2.2 is honored.

**Complexity**: medium

### T18. Move `aop/LeaderAspectOrder.kt`
- **Complexity**: low
- **Files**:
  - From: `leader-spring-boot-common/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderAspectOrder.kt`
  - To:   `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/LeaderAspectOrder.kt`
- **Action**: Move. Package stays `io.bluetape4k.leader.spring.aop`. No import changes expected.
- **Verify**: `ide_diagnostics` clean.

### T19. Move `aop/LeaderBeanSelector.kt`
- **Complexity**: low
- **Files**:
  - From: `leader-spring-boot-common/.../aop/LeaderBeanSelector.kt`
  - To:   `leader-spring-boot/.../aop/LeaderBeanSelector.kt`
- **Action**: Move. Package unchanged.
- **Verify**: `ide_diagnostics` clean.

### T20. Move `aop/LeaderElectionAspect.kt` + update annotation imports + pointcut FQCN ⚠️
- **Complexity**: medium
- **Files**:
  - From: `leader-spring-boot-common/.../aop/LeaderElectionAspect.kt`
  - To:   `leader-spring-boot/.../aop/LeaderElectionAspect.kt`
- **Action**:
  1. Move file (package unchanged).
  2. Replace `import io.bluetape4k.leader.spring.aop.LeaderElection` → `import io.bluetape4k.leader.annotation.LeaderElection`.
  3. Replace `import io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode` → `import io.bluetape4k.leader.annotation.LeaderAspectFailureMode` if present.
  4. Replace `import io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder` → `import io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder` if present.
  5. Replace `import io.bluetape4k.leader.spring.aop.metrics.SkipReason` → `import io.bluetape4k.leader.metrics.SkipReason` if present.
  6. **Critical**: Update `@Around("@annotation(io.bluetape4k.leader.spring.aop.LeaderElection)")` → `@Around("@annotation(io.bluetape4k.leader.annotation.LeaderElection)")`. String literal — compile silent.
- **Verify**:
  - `ide_diagnostics` clean.
  - `rg "io\.bluetape4k\.leader\.spring\.aop\.LeaderElection\b" leader-spring-boot/src/main/kotlin/.../LeaderElectionAspect.kt` returns 0.

### T21. Move `aop/LeaderGroupElectionAspect.kt` + update annotation imports + pointcut FQCN ⚠️
- **Complexity**: medium
- **Files**:
  - From: `leader-spring-boot-common/.../aop/LeaderGroupElectionAspect.kt`
  - To:   `leader-spring-boot/.../aop/LeaderGroupElectionAspect.kt`
- **Action**: Same as T20 but for `LeaderGroupElection`. Update `@Around("@annotation(io.bluetape4k.leader.spring.aop.LeaderGroupElection)")` → `@Around("@annotation(io.bluetape4k.leader.annotation.LeaderGroupElection)")`.
- **Verify**: `ide_diagnostics` clean; pointcut grep returns 0.

### T22. Move `aop/cache/FactoryCacheKey.kt` (contains BOTH FactoryCacheKey + GroupFactoryCacheKey)
- **Complexity**: low
- **Files**:
  - From: `leader-spring-boot-common/.../aop/cache/FactoryCacheKey.kt`
  - To:   `leader-spring-boot/.../aop/cache/FactoryCacheKey.kt`
- **Action**: Move single file. Spec §2.2 explicitly warns: do NOT split — both classes co-located.
- **Verify**: file still defines both classes; `ide_diagnostics` clean.

### T23. Move `aop/properties/LeaderAopProperties.kt` + update imports
- **Complexity**: low
- **Files**:
  - From: `leader-spring-boot-common/.../aop/properties/LeaderAopProperties.kt`
  - To:   `leader-spring-boot/.../aop/properties/LeaderAopProperties.kt`
- **Action**: Move; replace `import io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode` → `import io.bluetape4k.leader.annotation.LeaderAspectFailureMode`. Spec §2.2 import-update table.
- **Verify**: `ide_diagnostics` clean.

### T24. Move `aop/spel/SpelExpressionEvaluator.kt`
- **Complexity**: low
- **Files**:
  - From: `leader-spring-boot-common/.../aop/spel/SpelExpressionEvaluator.kt`
  - To:   `leader-spring-boot/.../aop/spel/SpelExpressionEvaluator.kt`
- **Action**: Move. No import changes expected (Spring SpEL types only).
- **Verify**: `ide_diagnostics` clean.

### T25. Move `aop/util/{AnnotationLookup,DurationParser,LockNameValidator}.kt`
- **Complexity**: low
- **Files**:
  - From: `leader-spring-boot-common/.../aop/util/AnnotationLookup.kt`, `DurationParser.kt`, `LockNameValidator.kt`
  - To:   `leader-spring-boot/.../aop/util/{...}.kt`
- **Action**: Move all three. Audit each for old SPI imports; rewrite if found.
- **Verify**: `ide_diagnostics` clean per file.

### T26. Move `aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt` + update imports
- **Complexity**: medium
- **Files**:
  - From: `leader-spring-boot-common/.../aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt`
  - To:   `leader-spring-boot/.../aop/validator/LeaderAnnotationValidatorBeanPostProcessor.kt`
- **Action**: Move; rewrite imports per spec §2.2 row 3:
  - `import io.bluetape4k.leader.spring.aop.LeaderElection` → `import io.bluetape4k.leader.annotation.LeaderElection`
  - `import io.bluetape4k.leader.spring.aop.LeaderGroupElection` → `import io.bluetape4k.leader.annotation.LeaderGroupElection`
  - Any `LeaderAspectFailureMode` import → annotation package.
- **Verify**: `ide_diagnostics` clean; reflection-based annotation lookup still references the new FQCN if any string literal usage exists (audit).

### T27. Move `properties/LeaderElectionProperties.kt` and `properties/LeaderGroupProperties.kt`
- **Complexity**: low
- **Files**:
  - From: `leader-spring-boot-common/src/main/kotlin/io/bluetape4k/leader/spring/properties/LeaderElectionProperties.kt`, `LeaderGroupProperties.kt`
  - To:   `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/properties/{...}.kt`
- **Action**: Move both. Package unchanged. Audit for old aop SPI imports (e.g., `LeaderAspectFailureMode`) and rewrite.
- **Verify**: `ide_diagnostics` clean.

### T28. Confirm deletions: LeaderAopHealthIndicator + LeaderElectionConfigSupport are NOT moved
- **Complexity**: low
- **Files**:
  - `leader-spring-boot-common/.../aop/health/LeaderAopHealthIndicator.kt` (must NOT be moved)
  - `leader-spring-boot-common/.../config/LeaderElectionConfigSupport.kt` (must NOT be moved)
- **Action**: No move. These are listed as deleted in spec §2.2 (Boot 4 incompat / no inheritors). Their tests are also deleted in T34.
- **Verify**: `ls leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/health/` does not exist; `ls .../config/LeaderElectionConfigSupport.kt` does not exist.

### T27a. ⚠️ Phase D common-source compile checkpoint (MANDATORY before Phase E)
- **Complexity**: low
- **Files**: none (verification only)
- **Action**: `./gradlew :leader-spring-boot:compileKotlin`. Phase D에서 common 소스 파일을 이동했으므로, Phase E 시작 전에 모든 import가 해결되어 있는지 확인한다. 컴파일 오류 발생 시 Phase E로 넘어가지 말고 해당 파일로 돌아가 수정한다.
  - 이 checkpoint는 common 소스(T18–T27)에 대한 검증만 수행. AutoConfiguration.imports, CTW 동작, boot4 소스 이동은 T32a에서 별도 확인.
- **Verify**: `compileKotlin` task BUILD SUCCESSFUL; `ide_diagnostics` returns zero unresolved imports for all files moved in T18–T27.

---

## Phase E — Move + rename sources from leader-spring-boot4

**Goal**: Move every source file under `io.bluetape4k.leader.spring.boot4.*` into `io.bluetape4k.leader.spring.*`. Rename `Boot4LeaderProperties` → `LeaderProperties`. Remove `@EnableAspectJAutoProxy`. Update annotation/SPI imports.

**Complexity**: medium

### T29. Move + rename top-level boot4 files (drop `boot4` package segment)
- **Complexity**: medium
- **Files** (representative; apply to ALL files under boot4):
  - `leader-spring-boot4/src/main/kotlin/io/bluetape4k/leader/spring/boot4/LeaderElectionAutoConfiguration.kt` → `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/LeaderElectionAutoConfiguration.kt`
  - `.../boot4/Boot4LeaderProperties.kt` → `.../spring/LeaderProperties.kt` (file + class rename)
  - `.../boot4/MongoCollectionProperties.kt` → `.../spring/MongoCollectionProperties.kt`
  - `.../boot4/adapter/PropertiesAdapter.kt` → `.../spring/adapter/PropertiesAdapter.kt`
  - `.../boot4/aop/autoconfigure/LeaderAopAutoConfiguration.kt` → `.../spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt`
  - `.../boot4/aop/autoconfigure/LeaderAopFactoryAutoConfiguration.kt` → `.../spring/aop/autoconfigure/LeaderAopFactoryAutoConfiguration.kt`
  - `.../boot4/backend/{ExposedJdbc,ExposedR2dbc,Hazelcast,Lettuce,Local,Mongo,Redisson}LeaderConfiguration.kt` → `.../spring/backend/{...}.kt`
  - `.../boot4/metrics/LeaderMicrometerAutoConfiguration.kt` → `.../spring/metrics/LeaderMicrometerAutoConfiguration.kt`
- **Action**: For every file:
  1. Move file to corresponding new path.
  2. Update `package io.bluetape4k.leader.spring.boot4...` → `package io.bluetape4k.leader.spring...`.
  3. Update every import of `io.bluetape4k.leader.spring.boot4.*` → `io.bluetape4k.leader.spring.*`.
  4. Update annotation/SPI imports per spec §2.2:
     - `io.bluetape4k.leader.spring.aop.LeaderElection` → `io.bluetape4k.leader.annotation.LeaderElection`
     - `io.bluetape4k.leader.spring.aop.LeaderGroupElection` → `io.bluetape4k.leader.annotation.LeaderGroupElection`
     - `io.bluetape4k.leader.spring.aop.LeaderAspectFailureMode` → `io.bluetape4k.leader.annotation.LeaderAspectFailureMode`
     - `io.bluetape4k.leader.spring.aop.metrics.LeaderAopMetricsRecorder` → `io.bluetape4k.leader.metrics.LeaderAopMetricsRecorder`
     - `io.bluetape4k.leader.spring.aop.metrics.SkipReason` → `io.bluetape4k.leader.metrics.SkipReason`
- **Note — local factory condition policy**: `LeaderAopFactoryAutoConfiguration`의 local factory beans (`localLeaderElectionFactory`, `localLeaderGroupElectionFactory`)은 name-based `@ConditionalOnMissingBean(name = [...])` 사용 중. Spec §3.2가 "type-based @ConditionalOnMissingBean" 으로 잘못 기술되어 있으나, **현재 동작(name-based, 항상 등록)을 그대로 유지**한다. Local과 backend factory가 공존하며 `LeaderBeanSelector`가 런타임에 적절한 factory를 선택한다. Spec §3.2는 T39에서 수정 문서화한다.
- **Verify**: After all moves, `rg "io\.bluetape4k\.leader\.spring\.boot4" leader-spring-boot/` returns 0; `ide_diagnostics` clean per file.

### T30. Rename class `Boot4LeaderProperties` → `LeaderProperties` (all references)
- **Complexity**: medium
- **Files**: every `.kt` referencing `Boot4LeaderProperties` (in newly moved sources + consumers).
- **Action**: Use IDE refactor (`ide_refactor_rename`) on the class symbol so all consumers update consistently. Cover:
  - The class declaration in `LeaderProperties.kt` (formerly `Boot4LeaderProperties.kt`) — rename class.
  - References inside `LeaderElectionAutoConfiguration.kt`, `PropertiesAdapter.kt`, backend configurations, etc.
  - `@ConfigurationProperties` `prefix` value should remain unchanged (`"bluetape4k.leader"`).
- **Verify**: `rg "Boot4LeaderProperties" leader-spring-boot/` returns 0.

### T31. Remove `@EnableAspectJAutoProxy` from `LeaderAopAutoConfiguration.kt` ⚠️ CRITICAL
- **Complexity**: low
- **Files**:
  - `leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/aop/autoconfigure/LeaderAopAutoConfiguration.kt`
- **Action**: Delete the `@EnableAspectJAutoProxy` annotation (and its `import org.springframework.context.annotation.EnableAspectJAutoProxy` line). Spec §3.1 mandates this — Freefair CTW already weaves at compile time; Spring proxy would double-advise.
- **Verify**: `rg "EnableAspectJAutoProxy" leader-spring-boot/` returns 0.

### T32. Confirm `@Around` pointcut FQCNs in moved aspects
- **Complexity**: low
- **Files**: `LeaderElectionAspect.kt`, `LeaderGroupElectionAspect.kt` (already in new location after T20/T21).
- **Action**: Re-grep both files to ensure neither contains `io.bluetape4k.leader.spring.aop.LeaderElection` or `LeaderGroupElection` in any string literal. They should reference `io.bluetape4k.leader.annotation.*`.
- **Verify**: `rg "leader\.spring\.aop\.Leader(Election|GroupElection)" leader-spring-boot/src/main/kotlin/.../aop/Leader*Aspect.kt` returns 0.

### T32a. ⚠️ Phase E post-compile checkpoint + ApplicationContext smoke test
- **Complexity**: medium
- **Files**: none (verification only)
- **Action**:
  1. `./gradlew :leader-spring-boot:compileKotlin processResources` — boot4 소스 이동 후 전체 컴파일 확인.
  2. `ApplicationContextRunner` 기반 smoke test 실행 (이미 T35에서 마이그레이션된 테스트 중 `LeaderElectionAutoConfigurationTest.kt`): boot4 소스 이동 후 AutoConfiguration이 정상 로드되는지 확인.
  3. T12c 결과에 따라 CTW 동작 확인: `DEBUG io.bluetape4k.leader.spring.aop.LeaderElectionAspect` 로그로 advice 실행 1회 검증.
- **Verify**: 컴파일 성공; `@EnableAspectJAutoProxy` 없이 advice가 1회 실행됨.

---

## Phase F — Move tests

**Goal**: Migrate tests from common + boot4 to `leader-spring-boot`. Delete tests for removed classes and all boot3 tests.

**Complexity**: low (mostly mechanical)

### T33. Move surviving leader-spring-boot-common tests
- **Complexity**: low
- **Files**: per spec §8.2, all of:
  - `LeaderElectionAspectTest.kt`
  - `LeaderGroupElectionAspectTest.kt`
  - `LeaderBeanSelectorTest.kt`
  - `LeaderAnnotationValidatorBeanPostProcessorTest.kt`
  - `LeaderAopPropertiesBindingTest.kt`
  - `FactoryCacheKeyTest.kt` (verify GroupFactoryCacheKey coverage included)
  - `SpelExpressionEvaluatorTest.kt`
  - `DurationParserTest.kt`
  - `LockNameValidatorTest.kt`
  - `LeaderElectionPropertiesTest.kt`
  - `LeaderGroupPropertiesTest.kt`
- **From**: `leader-spring-boot-common/src/test/kotlin/io/bluetape4k/leader/spring/...`
- **To**: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/...` (same sub-paths)
- **Action**: Move; update annotation/SPI imports identically to T20/T26 import rules. Update `LeaderAspectFailureMode` import in `LeaderAopPropertiesBindingTest.kt`.
- **Verify**: `ide_diagnostics` clean per moved test file.

### T34. Delete obsolete leader-spring-boot-common tests
- **Complexity**: low
- **Files**:
  - `leader-spring-boot-common/.../aop/health/LeaderAopHealthIndicatorTest.kt` (delete; class removed)
  - `leader-spring-boot-common/.../config/LeaderElectionConfigSupportTest.kt` (delete; class removed)
- **Action**: Delete files (do not move).
- **Verify**: files no longer present; no orphan imports elsewhere reference these classes.

### T35a. Boot3 테스트 audit (삭제 전 검증)
- **Complexity**: low
- **Files**: `leader-spring-boot3/src/test/kotlin/` (read-only scan)
- **Action**: `fd . leader-spring-boot3/src/test/kotlin/ --extension kt` 로 모든 boot3 테스트 클래스 목록 추출. 각 클래스에 대해 boot4 또는 `leader-spring-boot` 테스트에 동등한 커버리지가 존재하는지 확인. 매핑 테이블 작성:
  | boot3 test class | boot4/new equivalent | 상태 |
  |---|---|---|
  | `LeaderMicrometerAutoConfigurationBoot3Test.kt` | `LeaderMicrometerAutoConfigurationTest.kt` | 대체됨 |
  | ... | ... | ... |
  boot4 동등 테스트가 없는 boot3-only 테스트는 이관 후 T36 삭제 대신 `leader-spring-boot`로 이동.
- **Verify**: 모든 boot3 테스트 클래스가 "대체됨" 또는 "이관" 중 하나로 분류됨. "미분류" 항목 0.

### T35. Move + rename leader-spring-boot4 tests
- **Complexity**: low
- **Files**: per spec §8.3.
  - `AbstractRedissonAutoConfigurationTest.kt` — move; package rename `boot4` → `spring`.
  - `LeaderElectionAutoConfigurationTest.kt` — move; package rename.
  - `BackendConditionalTest.kt` — move; package rename.
  - `Boot4LeaderPropertiesBindingTest.kt` → rename file + class to `LeaderPropertiesBindingTest.kt`; update internal `Boot4LeaderProperties` → `LeaderProperties` references.
  - `adapter/PropertiesAdapterTest.kt` — move; package rename; `Boot4LeaderProperties` → `LeaderProperties`.
  - `metrics/LeaderMicrometerAutoConfigurationBoot4Test.kt` → rename to `LeaderMicrometerAutoConfigurationTest.kt`; package rename.
- **From**: `leader-spring-boot4/src/test/kotlin/io/bluetape4k/leader/spring/boot4/...`
- **To**: `leader-spring-boot/src/test/kotlin/io/bluetape4k/leader/spring/...`
- **Action**: Move + rename per above. Apply the same SPI/annotation import rewrites as Phase E.
- **Verify**: `ide_diagnostics` clean; `rg "boot4|Boot4LeaderProperties" leader-spring-boot/src/test/` returns 0.

### T36. Delete all leader-spring-boot3 tests
- **Complexity**: low
- **Files**: every test under `leader-spring-boot3/src/test/`.
- **Action**: Delete (the entire `leader-spring-boot3/` directory disappears in Phase I; this task confirms no test logic was unique-and-worth-saving). Spec §8.4: `LeaderMicrometerAutoConfigurationBoot3Test.kt` deleted; remaining boot3 tests have boot4 equivalents.
- **Verify**: confirm no boot3-only test contains coverage missing elsewhere — spec already audited.

---

## Phase G — Build config + docs cleanup

**Goal**: Update `settings.gradle.kts`, `leader-bom`, `CLAUDE.md` to reflect the new module list.

**Complexity**: low

### T37. settings.gradle.kts — 이미 Phase C에서 처리됨 (NOTE)
- **Complexity**: low
- **Action**: `include(":leader-spring-boot")` 추가는 T12a에서 완료됨. 구 모듈 `include` 제거는 **Phase I T37_cleanup에서 물리 디렉터리 삭제(T42-T44)와 동시에** 처리한다. 이 task는 T38 (BOM 최종 정리)과 함께 Phase I로 이동되었다. 이 Phase G 슬롯은 노트로만 남긴다.
- **Verify**: N/A (실제 작업은 T37_cleanup 참조).

### T38. leader-bom — 이미 Phase C에서 처리됨 (NOTE)
- **Complexity**: low
- **Action**: `:leader-spring-boot` 제약 추가는 T12b에서 완료됨. 구 모듈 제약 제거는 **Phase I T37_cleanup에서 settings 제거와 동시에** 처리한다. 이 Phase G 슬롯은 노트로만 남긴다.
- **Verify**: N/A (실제 작업은 T37_cleanup 참조).

### T39. Update `CLAUDE.md` module list + AOP guide + spec correction
- **Complexity**: low
- **Files**:
  - `CLAUDE.md`
  - `docs/superpowers/specs/2026-05-05-issue-104-spring-boot4-only-design.md` (spec §3.2 수정)
- **Action**:
  1. In the **Repository Layout** code block, remove the three old `leader-spring-boot*` lines and add `leader-spring-boot/         # Spring Boot 4 auto-configuration + AOP (Freefair CTW)`.
  2. In **Build Commands**, remove the three old gradle test invocations and add `./gradlew :leader-spring-boot:test`.
  3. In **AOP Annotation Guide → Key rules for annotated methods**: replace the Boot 3 / Boot 4 distinction with a single statement that `open` is no longer required (CTW-only).
  4. In **AutoConfiguration load order**: replace pre-existing block with the spec §3.3 ordering (5 entries).
  5. Add section **"AOP Factory Beans (Two distinct paths)"**:
     - **Election backend path**: `LocalLeaderConfiguration` → registers `LocalLeaderElection` / `LocalLeaderGroupElection` beans (application-level default backend, `@ConditionalOnMissingBean` type-based)
     - **AOP factory path**: `LeaderAopFactoryAutoConfiguration` → registers `{backend}LeaderElectionFactory` / `{backend}LeaderGroupElectionFactory` beans (AOP interceptor glue, name-based `@ConditionalOnMissingBean`). Local factory is always registered alongside backend factories; `LeaderBeanSelector` selects the right one at runtime.
  6. **Spec §3.2 수정**: "LocalLeaderElectionFactory uses type-level @ConditionalOnMissingBean" 문구를 실제 동작(name-based, 항상 등록)으로 교정.
- **Verify**: rendered file lists correct modules; no boot3/boot4 references remain; local factory behavior documented accurately.

---

## Phase H — GitHub Workflows

**Goal**: Reflect module changes in CI + nightly builds.

**Complexity**: low

### T40. Update `.github/workflows/ci.yml`
- **Complexity**: low
- **Files**:
  - `.github/workflows/ci.yml`
- **Action**:
  1. Add `test-spring-boot` job (Redis + MongoDB Testcontainers env per spec §7).
  2. Add `- test-spring-boot` to `coverage-report.needs` and `ci-status.needs` arrays.
  3. Remove any `test-spring-boot-common`, `test-spring-boot3`, or `test-spring-boot4` job references if present in `ci.yml` (most live in nightly, but double-check).
- **Verify**: `gh workflow view ci.yml` (or YAML lint) shows new job; `needs:` arrays reference it.

### T41. Update `.github/workflows/nightly.yml`
- **Complexity**: low
- **Files**:
  - `.github/workflows/nightly.yml`
- **Action**:
  1. Delete jobs `test-spring-boot-common`, `test-spring-boot3`, `test-spring-boot4`.
  2. Add job `test-spring-boot` with the same Testcontainer env as the deleted boot4 job (Redis + MongoDB).
  3. Update `coverage-report.needs` and `nightly-status.needs` arrays accordingly (remove 3, add 1).
- **Verify**: YAML lint clean; needs arrays balanced.

---

## Phase I — Delete obsolete modules

**Goal**: Physically remove the three retired module directories. Only safe AFTER Phases A–H pass and the new module compiles + tests pass against new packages.

**Complexity**: low

### T37_cleanup. ⚠️ settings + BOM 구 모듈 제거 (atomic, BEFORE T42–T44)
- **Complexity**: low
- **Files**:
  - `settings.gradle.kts`
  - `leader-bom/build.gradle.kts`
- **Action**: 두 파일을 **같은 커밋**에서 처리한다:
  1. `settings.gradle.kts`: `include(":leader-spring-boot-common")`, `include(":leader-spring-boot3")`, `include(":leader-spring-boot4")` 세 줄 제거.
  2. `leader-bom/build.gradle.kts`: constraints 블록에서 구 3개 모듈 `api(project(...))` 제거.
  - **이유**: settings에서 모듈 제거 후 BOM이 해당 모듈을 `project(...)` 참조하면 Gradle 프로젝트 평가 실패. 두 파일은 반드시 함께 수정해야 한다.
- **Verify**: `./gradlew projects` 출력에서 구 3개 모듈 없음; `./gradlew :leader-bom:build` 성공.

### T42. Delete `leader-spring-boot-common/` directory
- **Complexity**: low
- **Files**:
  - entire directory `leader-spring-boot-common/`
- **Action**: `git rm -rf leader-spring-boot-common/`. Do NOT proceed if any consumer module still imports the old packages — confirm via `rg "leader-spring-boot-common|io\.bluetape4k\.leader\.spring\.aop\.metrics\."` returns 0 outside of `docs/`.
- **Verify**: directory absent; build scripts no longer reference it.

### T43. Delete `leader-spring-boot3/` directory
- **Complexity**: low
- **Files**:
  - entire directory `leader-spring-boot3/`
- **Action**: `git rm -rf leader-spring-boot3/`.
- **Verify**: directory absent.

### T44. Delete `leader-spring-boot4/` directory
- **Complexity**: low
- **Files**:
  - entire directory `leader-spring-boot4/`
- **Action**: `git rm -rf leader-spring-boot4/`.
- **Verify**: directory absent; `rg "io\.bluetape4k\.leader\.spring\.boot4"` returns 0 across the repo (excluding `docs/`).

---

## Phase J — Full build + test verification

**Goal**: Prove the consolidated state compiles, tests pass, and DoD greps are clean.

**Complexity**: high

### T45. Run `ide_diagnostics` on all changed Kotlin files
- **Complexity**: medium
- **Files**: every `.kt` touched in Phases A, B, D, E, F.
- **Action**: For each, call `ide_diagnostics`. Resolve any unresolved imports with `ide_optimize_imports`. Apply Quick Fixes for any `@Deprecated`. Project rule (CLAUDE.md "Kotlin Edit Workflow"): never compile until diagnostics are clean.
- **Verify**: zero unresolved imports, zero deprecation warnings remaining.

### T46. Build `:leader-spring-boot`
- **Complexity**: medium
- **Files**: none (verification only)
- **Action**: `./gradlew :leader-spring-boot:build`.
- **Verify**: BUILD SUCCESSFUL; aspectj weaving log lines visible (Freefair CTW); tests compiled.

### T47. Run `:leader-spring-boot` tests
- **Complexity**: high
- **Files**: none (verification only)
- **Action**: `./gradlew :leader-spring-boot:test`.
- **Verify**: all tests pass. If failures relate to:
  - **Pointcut not matching** → re-check T20/T21 string literal updates.
  - **Double-advice / lock acquired twice** → re-check T31 `@EnableAspectJAutoProxy` removal.
  - **AutoConfiguration ordering** → confirm `AutoConfiguration.imports` order from T14.

### T48. Run full multi-module build (no tests)
- **Complexity**: medium
- **Files**: none (verification only)
- **Action**: `./gradlew build -x test --parallel`.
- **Verify**: every module compiles. Catches stragglers in `leader-bom`, settings, or backend modules that may still reference old packages.

### T49. Run `:leader-core:build` and `:leader-micrometer:test`
- **Complexity**: low
- **Files**: none (verification only)
- **Action**: Re-run `./gradlew :leader-core:build :leader-micrometer:test` to confirm nothing regressed.
- **Verify**: BUILD SUCCESSFUL on both.

### T50. Kover coverage gate ≥ 60% for leader-spring-boot
- **Complexity**: low
- **Files**: none (verification only)
- **Action**: `./gradlew :leader-spring-boot:koverVerify`. Project memory: feedback_kover_unit_test_only_threshold — 60% is the standard for Spring Boot integration modules (full coverage measured via nightly).
- **Verify**: passes the rule defined in T13.

### T51. DoD grep gate
- **Complexity**: low
- **Files**: none (verification only)
- **Action**: Run the spec §9 grep:
  ```
  rg "io\.bluetape4k\.leader\.spring\.boot[34]|leader-spring-boot[34]|leader-spring-boot-common|Boot4LeaderProperties|LeaderAopHealthIndicator|LeaderElectionConfigSupport|LeaderMicrometerHealthAutoConfiguration" \
     --type-add 'imports:*.imports' --type kt --type kts --type yml --type json --type imports \
     -g '!docs/**'
  ```
  Plus the targeted greps:
  - `rg "leader\.spring\.aop\.Leader(Election|GroupElection)" leader-spring-boot/src/main/kotlin/.../aop/Leader*Aspect.kt`
  - `rg "leader\.spring\.aop\.LeaderAspectFailureMode" leader-spring-boot/src/main/resources/`
- **Verify**: every grep returns 0 hits. (Gradle catalog identifiers like `libs.plugins.spring.boot4` and `libs.spring.boot4.dependencies` are intentionally not matched by the regex above.)

### T52. Step 6-R 6-Tier code review
- **Complexity**: medium
- **Files**: full diff of the issue branch.
- **Action**: Run bluetape4k-design Step 6-R 6-Tier review (factual, senior engineer, security, consistency, performance, doc). Project memory: feedback_pr_code_review — mandatory before PR.
- **Verify**: zero CRITICAL, zero HIGH; address MEDIUMs that touch this PR.

### T53. README updates for leader-spring-boot
- **Complexity**: low
- **Files**:
  - `leader-spring-boot/README.md` (new)
  - `leader-spring-boot/README.ko.md` (new)
- **Action**: Author module READMEs covering: purpose, dependency snippet, AOP usage with the new `io.bluetape4k.leader.annotation.*` imports, CTW-only weaving note (no `open` required), AutoConfiguration order. Reference DoD §"README.md + README.ko.md 업데이트".
- **Verify**: both files exist with parallel content (KR/EN).

---

## Task summary table

| ID | Phase | Complexity | One-liner |
|----|-------|------------|-----------|
| T1 | A | low | Create `annotation/LeaderElection.kt` in leader-core |
| T2 | A | low | Create `annotation/LeaderGroupElection.kt` in leader-core |
| T3 | A | low | Create `annotation/LeaderAspectFailureMode.kt` in leader-core |
| T4 | A | low | Create `metrics/LeaderAopMetricsRecorder.kt` in leader-core |
| T5 | A | low | Create `metrics/SkipReason.kt` in leader-core |
| T6 | A | low | Add 5 unit-test files for new annotations + metrics SPI |
| T7 | A | low | Build verify: `./gradlew :leader-core:build` |
| T8 | B | low | Swap `leader-micrometer` Gradle dep to `:leader-core` |
| T9 | B | low | Update `MicrometerLeaderAopMetricsRecorder.kt` imports |
| T10 | B | low | Update leader-micrometer test imports |
| T11 | B | low | Build verify: `./gradlew :leader-micrometer:test` |
| T12a | C | low | ⚠️ settings.gradle.kts — 신규 모듈 추가만 (구 모듈 유지) |
| T12b | C | low | ⚠️ BOM에 신규 모듈 제약 추가 (구 제약 유지) |
| T12c | C | medium | ⚠️ CTW 단독 동작 검증 (proof task) |
| T12 | C | low | Create directory structure for leader-spring-boot |
| T13 | C | medium | Write `leader-spring-boot/build.gradle.kts` |
| T14 | C | low | Write `AutoConfiguration.imports` |
| T15 | C | low | Write `additional-spring-configuration-metadata.json` |
| T16 | C | low | Write `junit-platform.properties` |
| T17 | C | low | Write `logback-test.xml` |
| T18 | D | low | Move `LeaderAspectOrder.kt` |
| T19 | D | low | Move `LeaderBeanSelector.kt` |
| T20 | D | medium | Move `LeaderElectionAspect.kt` + update annotation/pointcut FQCN ⚠️ |
| T21 | D | medium | Move `LeaderGroupElectionAspect.kt` + update annotation/pointcut FQCN ⚠️ |
| T22 | D | low | Move `aop/cache/FactoryCacheKey.kt` (with GroupFactoryCacheKey) |
| T23 | D | low | Move `LeaderAopProperties.kt` + update imports |
| T24 | D | low | Move `SpelExpressionEvaluator.kt` |
| T25 | D | low | Move `aop/util/{AnnotationLookup,DurationParser,LockNameValidator}.kt` |
| T26 | D | medium | Move `LeaderAnnotationValidatorBeanPostProcessor.kt` + update imports |
| T27 | D | low | Move `properties/{LeaderElection,LeaderGroup}Properties.kt` |
| T28 | D | low | Confirm `LeaderAopHealthIndicator` + `LeaderElectionConfigSupport` are NOT moved |
| T27a | D | low | ⚠️ Phase D common-source compile checkpoint |
| T29 | E | medium | Move + repackage all boot4 sources (drop `boot4` segment); local factory note |
| T30 | E | medium | Rename class `Boot4LeaderProperties` → `LeaderProperties` |
| T31 | E | low | Remove `@EnableAspectJAutoProxy` from `LeaderAopAutoConfiguration` ⚠️ |
| T32 | E | low | Confirm `@Around` pointcut FQCNs are migrated |
| T32a | E | medium | ⚠️ Phase E post-compile checkpoint + ApplicationContext smoke test |
| T33 | F | low | Move surviving leader-spring-boot-common tests |
| T34 | F | low | Delete obsolete common tests (Health + ConfigSupport) |
| T35a | F | low | ⚠️ Boot3 test audit (삭제 전 매핑 테이블) |
| T35 | F | low | Move + rename leader-spring-boot4 tests |
| T36 | F | low | Delete all leader-spring-boot3 tests (audit 기반) |
| T37 | G | low | settings — Phase C T12a에서 처리됨 (note only) |
| T38 | G | low | BOM — Phase C T12b에서 처리됨 (note only) |
| T39 | G | low | Update `CLAUDE.md` module list + AOP guide |
| T40 | H | low | Update `.github/workflows/ci.yml` |
| T41 | H | low | Update `.github/workflows/nightly.yml` |
| T37_cleanup | I | low | ⚠️ settings + BOM 구 모듈 제거 (atomic) |
| T42 | I | low | Delete `leader-spring-boot-common/` |
| T43 | I | low | Delete `leader-spring-boot3/` |
| T44 | I | low | Delete `leader-spring-boot4/` |
| T45 | J | medium | Run `ide_diagnostics` on all changed `.kt` files |
| T46 | J | medium | `./gradlew :leader-spring-boot:build` |
| T47 | J | high | `./gradlew :leader-spring-boot:test` |
| T48 | J | medium | `./gradlew build -x test --parallel` |
| T49 | J | low | Re-run `:leader-core:build` + `:leader-micrometer:test` |
| T50 | J | low | `./gradlew :leader-spring-boot:koverVerify` (≥60%) |
| T51 | J | low | DoD grep gate (zero hits) |
| T52 | J | medium | Step 6-R 6-Tier code review |
| T53 | J | low | Author `leader-spring-boot/README.md` + `README.ko.md` |

Total: 53 tasks across 10 phases.

---

## Open questions / non-goals

- **`LeaderAopHealthIndicator` Boot 4 rewrite** — out of scope (tracked separately under issue #80 per spec §3.4).
- **Migration guide / CHANGELOG entries** — spec §3.5 lists the breaking-change items; assumed to land in the PR description, not a separate CHANGELOG file. Confirm with maintainer if a `CHANGELOG.md` already exists.
- **Local-factory ambiguity policy** — spec §3.2 states "no change". This plan does not touch `LeaderBeanSelector` semantics; only its module location moves.

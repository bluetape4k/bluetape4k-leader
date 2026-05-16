# Lesson: Explicit @TestInstance(PER_CLASS) in leader-core test classes

**Date**: 2026-05-16
**Issue**: #268
**PR**: TBD

## Root Cause

Six concrete test classes in `leader-core` were missing the explicit
`@TestInstance(TestInstance.Lifecycle.PER_CLASS)` annotation. While
`junit-platform.properties` already sets `junit.jupiter.testinstance.lifecycle.default=per_class`
globally, the annotation was absent — inconsistent with the project convention of
making the lifecycle explicit on each class.

`LeaderStateTest` additionally lacked a `companion object : KLogging()`.

## Decision

Add `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` + `import org.junit.jupiter.api.TestInstance`
to all six classes. Add `companion object : KLogging()` to `LeaderStateTest`.

The annotation is technically redundant (properties file covers it) but:
- Makes the lifecycle visible without reading `resources/`
- Consistent with all other test classes in the project
- Required by project convention (CLAUDE.md)

## Files Changed

- `LeaderStateTest.kt` — annotation + companion object + import
- `LeaderGroupElectionStateTest.kt` — annotation + import
- `AsyncLeaderElectorContractTest.kt` — annotation + import
- `AsyncLeaderGroupElectorContractTest.kt` — annotation + import
- `LeaderElectionOptionsTest.kt` — annotation + import
- `LeaderGroupElectionOptionsTest.kt` — annotation + import

## Verification

`./gradlew :leader-core:test` — BUILD SUCCESSFUL (no test failures)

## Future Guidance

When adding new test classes to `leader-core` (or any module): always include
`@TestInstance(TestInstance.Lifecycle.PER_CLASS)` and `companion object : KLogging()`
even if `junit-platform.properties` already sets the default lifecycle.

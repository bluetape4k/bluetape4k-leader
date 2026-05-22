# Lesson: Close deprecated API cleanup with source evidence

**Date**: 2026-05-23
**Issue**: #269

## Finding

Issue #269 was a follow-up after #264 and #271. By the time this lane started,
the remaining `@Deprecated` API removals and suspend extend-delegate
`runBlocking` bridge removals had already landed.

## Evidence

- `rg -n "@Deprecated|DeprecationLevel\\.ERROR" --glob '*.kt'` returned no
  matches in source.
- `rg --files -g '*LettuceSemaphore*.kt' -g '*LettuceSuspendSemaphore*.kt'`
  returned no matches in the current tree.
- `git show --name-status 3c78fd75` shows `LettuceSemaphore.kt`,
  `LettuceSuspendSemaphore.kt`, and `LettuceSemaphoreTest.kt` deleted by
  `refactor: remove all @Deprecated APIs (10 items) (#274)`.
- Issue #264 is closed and removed the 0.1.0 deprecated API list.
- Issue #271 is closed and removed the targeted suspend-to-blocking extend
  delegate bridge pattern.
- The only stale references were documentation statements that the legacy
  Lettuce semaphore classes still existed as deprecated source.

## Decision

Close #269 with a small documentation correction PR instead of inventing a code
change. The runtime API surface already satisfies the cleanup requirement; the
remaining risk was stale migration documentation.

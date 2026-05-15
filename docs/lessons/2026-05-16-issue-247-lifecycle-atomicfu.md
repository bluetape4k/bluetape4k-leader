# Issue 247 Lifecycle AtomicFU

## Context

`LeaderLeaseAutoExtenderLifecycle` is part of the virtual-thread-aware Spring Boot module. The lifecycle lock had already been moved to `ReentrantLock.withLock` on current `develop`, but class-level lifecycle counters still used `java.util.concurrent.atomic.*`.

## Decision

Use `kotlinx.atomicfu.atomic` for the instance `registered` flag and companion `activeContextCount`, keeping the existing `ReentrantLock.withLock` lifecycle critical section unchanged.

## Outcome

The lifecycle class no longer imports `java.util.concurrent.atomic.*` and does not use `synchronized {}`. Existing idempotency and multi-context behavior stays unchanged.

## Verification

- `./gradlew :leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.LeaderLeaseAutoExtenderLifecycleTest' --no-configuration-cache --console=plain`
- Result: 6 tests passing, build successful.
- `git diff --check`
- `rg "java\\.util\\.concurrent\\.atomic|synchronized\\(" leader-spring-boot/src/main/kotlin/io/bluetape4k/leader/spring/boot/LeaderLeaseAutoExtenderLifecycle.kt` returned no matches.

## Future Notes

For class-level lifecycle or shared state in virtual-thread-aware Spring code, prefer atomicfu plus explicit locks. Java atomics remain acceptable for local test counters and non-virtual-thread-aware surfaces.

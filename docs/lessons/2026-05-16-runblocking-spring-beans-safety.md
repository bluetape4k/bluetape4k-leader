# Lesson: runBlocking in Spring Boot bean initializers is safe by design

**Date**: 2026-05-16
**Issue**: #263
**PR**: #276

## Root Cause Investigation

Four `@Bean` methods across two Spring Boot auto-configuration classes used
`runBlocking { }` to bridge suspend constructors (schema / TTL-index initialization)
into the synchronous Spring bean factory:

- `ExposedR2dbcLeaderConfiguration.exposedR2dbcSuspendLeaderElector`
- `ExposedR2dbcLeaderConfiguration.exposedR2dbcSuspendLeaderGroupElector`
- `MongoLeaderConfiguration.mongoSuspendLeaderElector`
- `MongoLeaderConfiguration.mongoSuspendLeaderGroupElector`

The concern was whether `runBlocking` could pin virtual-thread carriers and cause
thread starvation under Spring Boot virtual-thread executor.

## Investigation Result

`rg "synchronized|@Synchronized"` in `leader-mongodb/src/main` and
`leader-exposed-r2dbc/src/main` returned **zero matches**.

Virtual threads only pin their carrier thread inside `synchronized` blocks or
`Object.wait()` calls. Since neither the coroutine body nor any code reachable
from the suspend constructors uses `synchronized`, the carrier is free to unmount
and serve other virtual threads while the coroutine suspends on IO. There is **no
pinning risk**.

## Conclusion

The existing `runBlocking` usage is correct and intentional:

1. Spring bean initialization runs on a platform thread (or a virtual thread
   without carrier-pinning code). Either way, `runBlocking` is safe.
2. The block is called **once** at startup; it has no impact on steady-state
   throughput.
3. This matches the CLAUDE.md allowance: _"Do not use `runBlocking` in production
   code except tightly controlled lazy initialization."_

## Changes Made

No logic changes. Updated KDoc on both configuration classes from Korean to
English and added an explicit note explaining the virtual-thread carrier-pinning
analysis so future contributors do not need to re-investigate.

## Future Guidance

Before filing a `runBlocking` pinning bug:

1. Run `rg "synchronized|@Synchronized"` inside the `runBlocking { }` call tree.
2. If the result is zero matches, `runBlocking` is safe on virtual threads.
3. Carrier pinning only occurs inside `synchronized` / `Object.wait()` — not from
   `runBlocking` itself.
4. Document the safety analysis in the KDoc so the finding is durable.

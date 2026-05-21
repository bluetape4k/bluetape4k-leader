# Issue 271 Suspend Extend Delegate

## Context

Issue #271 removed `runBlocking` bridges from coroutine-native backend extend delegates.
Affected modules were Lettuce, Redisson, MongoDB, Hazelcast, and Exposed R2DBC.

## Decision

Introduce `SuspendExtendDelegate` as the coroutine-native SPI and route suspend electors to the new
`LeaderLeaseAutoExtender.start(..., SuspendExtendDelegate, ...)` overload by statically typing delegate locals.
The suspend watchdog keeps scheduler cadence on the existing executor but runs backend extend in a private
coroutine scope, without moving `runBlocking` into the core watchdog.

## Outcome

Suspend `LockExtender` and watchdog paths now call `extendSuspend()` directly. Sync misuse of a
`SuspendExtendDelegate` returns `BackendError(UnsupportedOperationException)` and `isHeld()` returns false,
so accidental sync calls fail visibly instead of blocking.

## Verification

- `./gradlew :bluetape4k-leader-core:test`
- `./gradlew :bluetape4k-leader-core:test :bluetape4k-leader-redis-lettuce:compileKotlin :bluetape4k-leader-redis-redisson:compileKotlin :bluetape4k-leader-mongodb:compileKotlin :bluetape4k-leader-hazelcast:compileKotlin :bluetape4k-leader-exposed-r2dbc:compileKotlin`
- `rg -n "runBlocking|: ExtendDelegate|import io\\.bluetape4k\\.leader\\.internal\\.ExtendDelegate" ... -g '*Suspend*ExtendDelegate.kt'` returned no matches for targeted backend modules.

## Future Guidance

When adding a coroutine-native backend, implement `SuspendExtendDelegate`, type the elector delegate local as
`SuspendExtendDelegate`, and rethrow `CancellationException` before any broad `catch (Exception)` in suspend
delegate methods.

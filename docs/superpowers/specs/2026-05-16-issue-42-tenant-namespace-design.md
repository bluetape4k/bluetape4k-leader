# Issue 42 Tenant Lock Namespace Design

## Context

Issue #42 requests multi-tenancy support by isolating lock names per tenant without backend changes. Current leader APIs accept a caller-provided `lockName`; every backend already treats that value as the lock identity. The lightest compatible design is therefore a decorator that rewrites public lock names into namespaced backend lock names.

Current evidence:

- `LeaderElector`, `LeaderGroupElector`, `SuspendLeaderElector`, and `SuspendLeaderGroupElector` all expose `runIfLeader(lockName, ...)`.
- State APIs also take `lockName`, so state snapshots must use the same namespace mapping.
- `validateLockName()` allows alphanumeric first character and then alphanumeric, `_`, `-`, `:` up to 255 chars. `tenant:<tenantId>:<lockName>` fits when `tenantId` and `lockName` follow the same rule.
- Existing `withListeners()` decorators provide a local pattern for backend-independent wrappers.

## Goals

- Add tenant-scoped wrappers in `leader-core`.
- Keep backend implementations unchanged.
- Support blocking single leader, blocking group leader, coroutine single leader, and coroutine group leader.
- Support virtual-thread single/group leader interfaces because they are separate lock-name-based public contracts.
- Preserve result, exception, cancellation, state, and async behavior of delegates.
- Validate generated backend lock names before delegating.

## Non-Goals

- No new backend schema.
- No tenant-aware AOP annotation in this issue.
- No persistence of tenant metadata separate from the lock name.
- No new module.

## API

```kotlin
val tenantElection = election.forTenant("acme")
tenantElection.runIfLeader("report-job") { generateReport() }
```

Public additions:

```kotlin
data class TenantLockNamespace(
    val tenantId: String,
    val prefix: String = TenantLockNamespace.DefaultPrefix,
) : Serializable

fun TenantLockNamespace.lockName(lockName: String): String

fun LeaderElector.forTenant(tenantId: String): LeaderElector
fun LeaderElector.forTenant(namespace: TenantLockNamespace): LeaderElector

fun LeaderGroupElector.forTenant(tenantId: String): LeaderGroupElector
fun LeaderGroupElector.forTenant(namespace: TenantLockNamespace): LeaderGroupElector

fun SuspendLeaderElector.forTenant(tenantId: String): SuspendLeaderElector
fun SuspendLeaderElector.forTenant(namespace: TenantLockNamespace): SuspendLeaderElector

fun SuspendLeaderGroupElector.forTenant(tenantId: String): SuspendLeaderGroupElector
fun SuspendLeaderGroupElector.forTenant(namespace: TenantLockNamespace): SuspendLeaderGroupElector

fun VirtualThreadLeaderElector.forTenant(tenantId: String): VirtualThreadLeaderElector
fun VirtualThreadLeaderElector.forTenant(namespace: TenantLockNamespace): VirtualThreadLeaderElector

fun VirtualThreadLeaderGroupElector.forTenant(tenantId: String): VirtualThreadLeaderGroupElector
fun VirtualThreadLeaderGroupElector.forTenant(namespace: TenantLockNamespace): VirtualThreadLeaderGroupElector
```

`TenantLockNamespace` exists because prefix and tenant id are same-typed values with different meanings. The convenience `forTenant(tenantId: String)` keeps the issue's simple call site.

## Behavior

- `TenantLockNamespace("acme").lockName("report-job")` returns `tenant:acme:report-job`.
- Wrappers translate every caller-facing `lockName` before calling the delegate.
- State methods return delegate snapshots for the namespaced backend lock. The returned `LeaderState.lockName` / `LeaderGroupState.lockName` stays namespaced because it reflects backend state.
- Slot overloads translate `LeaderSlot.lockName` but preserve `LeaderSlot.leaderId`.
- Async wrappers delegate `runAsyncIfLeader` with the translated lock name and preserve the provided `Executor`.
- Virtual-thread wrappers delegate `runAsyncIfLeader` with the translated lock name and preserve `VirtualFuture` behavior.
- Suspend wrappers do not use `runCatching`; cancellation behavior remains delegate-defined and is not swallowed.

## Validation

- `tenantId` and `prefix` must be non-blank and must not contain `:`.
- Caller-facing tenant-scoped `lockName` must be non-blank and must not contain `:`. The separator is reserved so `(tenantId, lockName)` mapping is injective.
- The final generated lock name must pass `validateLockName()`.
- A final lock name over 255 chars fails fast with `IllegalArgumentException`.
- Error messages include the remaining `lockName` length budget for the selected prefix and tenant id.

Colon is reserved as the namespace separator. This prevents ambiguous values such as `tenantId = "a:b"` or `lockName = "foo:bar"` producing indistinguishable nested namespaces.

## Rejected Options

| Option | Reason |
|---|---|
| Backend-specific tenant fields | Requires schema and backend changes, contrary to issue scope. |
| Free-form string concatenation in each wrapper | Repeats validation and risks inconsistent lock naming. |
| Public `forTenant(tenantId: String, prefix: String)` | Two same-typed parameters are easy to swap; use `TenantLockNamespace` for custom prefix. |
| Returning unscoped `LeaderState.lockName` | Would hide the actual backend lock identity and complicate diagnostics. |
| Excluding virtual-thread interfaces | They do not inherit `LeaderElector`; excluding them would leave a public lock-name path without tenant scoping. |

## Acceptance Criteria

- `LeaderElector.forTenant()` extension exists.
- `SuspendLeaderElector.forTenant()` extension exists.
- `LeaderGroupElector.forTenant()` extension exists.
- `SuspendLeaderGroupElector.forTenant()` extension exists.
- `VirtualThreadLeaderElector.forTenant()` and `VirtualThreadLeaderGroupElector.forTenant()` extensions exist.
- Tenant-scoped wrappers translate run, async, result, slot, and state calls.
- Unit tests cover translation, validation, length budget failures, state calls, slot leader id preservation, async delegation, virtual-thread delegation, and suspend cancellation propagation.
- README and README.ko mention tenant namespace usage.

## DoD

- IDE diagnostics have zero errors for touched Kotlin files.
- `:leader-core:test` targeted tenant namespace tests pass.
- Tier 4 + Claude advisor review P0/P1 = 0.
- Lessons entry committed before PR creation.

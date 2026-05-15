# Issue 42 Tenant Namespace

## Context

Tenant isolation was requested without backend changes. The implementation had to work across blocking, coroutine, group, and virtual-thread leader APIs.

## Decision

Use backend-independent decorators that translate caller-facing lock names through `TenantLockNamespace`. Reserve `:` as the namespace separator and reject it in tenant-local values so `(tenantId, lockName)` maps injectively to one backend lock name.

## Outcome

`forTenant()` wrappers now exist for blocking, coroutine, group, and virtual-thread electors. The wrappers translate state, run, async, result, and slot paths while preserving slot `leaderId`.

## Verification

- Spec/plan Claude advisor found namespace bypass and separator-injection risks; spec/plan updated before implementation.
- Targeted tests: `TenantScopedLeaderElectorsTest` and `TenantScopedSuspendLeaderElectorsTest`.
- Result: 14 tests passing, build successful.
- README/README.ko API names verified against source.
- Post-PR Claude feedback added group slot/result coverage and README/README.ko migration caveats for `:` and backend `state().lockName`.

## Future Notes

When adding decorators over leader APIs, avoid Kotlin interface delegation unless every lock-name-taking inherited method has been reviewed. Async, state, slot, and result overloads are easy bypass paths.

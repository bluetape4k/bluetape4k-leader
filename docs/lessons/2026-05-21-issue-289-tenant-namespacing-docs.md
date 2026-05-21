# Issue 289 tenant namespacing docs

## Context

`leader-core` already exposes `TenantLockNamespace` and `forTenant()` wrappers,
but the module README pair did not describe them. Top-level README files had the
tenant namespacing guidance, so module-only readers could miss the feature.

## Decision

Add a focused `Tenant Namespacing` section to `leader-core/README.md` and
`leader-core/README.ko.md` near the state inspection contract. Document the
actual implementation contract: generated backend names use
`prefix:tenantId:lockName`, `:` is reserved in all namespace parts and
tenant-local lock names, and the final name must fit the 255-character shared
lock-name limit.

## Outcome

The module README pair now covers `TenantLockNamespace`, `forTenant()`, custom
prefixes, separator restrictions, the lock-name length budget, and blocking/group
usage examples.

## Verification

- `rg -n "Tenant Namespacing|TenantLockNamespace|forTenant|255|batch:daily|tenant-a|테넌트 네임스페이스|255자" leader-core/README.md leader-core/README.ko.md`
- `git diff --check`

## Future Agents

When issue text disagrees with source code, document the source-code contract.
For this issue the body mentioned `::`, but `TenantLockNamespace` reserves a
single `:` separator.

# Kover Coverage Policy

## Current Status

`bluetape4k-leader` already enforces Kover verification bounds for selected
modules:

| Module | Threshold | Rationale |
|---|---:|---|
| `leader-core` | 80% | Core public API and contract logic. |
| `leader-micrometer` | 80% | Metrics export behavior is unit/integration-testable. |
| `leader-zookeeper` | 80% | Backend contract has stable Testcontainers coverage. |
| `leader-spring-boot` | 60% | Spring Boot auto-configuration plus AspectJ CTW integration. |

## Policy

Status: enforced for validated modules; documented integration-heavy exceptions
elsewhere.

Backend modules that depend on Redis, MongoDB, Exposed, Hazelcast, or Ktor stay
report-only until each has a measured baseline and a realistic threshold.

## Threshold Plan

- Keep existing bounds enforced in Nightly.
- Add backend-specific thresholds only after stable module coverage is measured.
- Prefer 80% for pure contract modules and 60-70% for integration-heavy
  backends.

## CI/Nightly Contract

Nightly must run `koverVerify` for every module listed in the enforced table.

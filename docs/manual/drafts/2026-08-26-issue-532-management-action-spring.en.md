---
title: "Spring Actuator management actions"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Spring Actuator management actions

> Unreleased draft for Issue #532. This document is not part of the pinned `0.5.0` manual because the API is newer than `sourceReleaseCommit`. Promote it only after a release manifest points at a commit containing this API.

## Scope

The action surface releases a registered single-leader lease through a separate HTTP
Actuator endpoint. It is an operational escape hatch, not a replacement for normal
`runIfLeader` execution and not a force-unlock API. Group, strategic, route-runtime,
and scheduled execution are never registered automatically.

## Explicit opt-in

Both the existing parent endpoint and the nested action property must be enabled. The
library default is fail-closed:

```yaml
management:
  endpoint:
    leader-election:
      enabled: true
      actions:
        enabled: true
        timeout: 5s
  endpoints:
    web:
      exposure:
        include: health,leaderElection,leaderElectionActions
```

Spring relaxed binding accepts `leader-election` and `leaderElection`; new files should
use kebab-case. The write endpoint ID is `leaderElectionActions`, and its path is
`POST /actuator/leaderElectionActions/{lockName}`. The existing read-only
`leaderElection` endpoint and its JMX descriptor are unchanged. The action endpoint is
`@WebEndpoint`, not a JMX write operation.

The library does not install a `SecurityFilterChain`. Keep Actuator authentication,
authorization, network policy, and audit ownership in the application.

## Registry ownership

When no `LeaderManagementActionRegistry` bean exists, auto-configuration creates a
bounded library-owned registry with a five-second action timeout and drains it before
the Spring context closes. An application-provided registry wins; its observer,
executor, scope, registration tokens, and lifecycle remain application-owned and are
not replaced or closed by this module.

Register the lease handle at the application-owned acquisition boundary. Closing a
`LeaderManagementRegistration` is idempotent and removes eligibility; it does not
release the lease itself. The registry performs ownership pre-check, one conditional
release, and post-check before returning a result.

## Response and retry contract

The response body is allow-listed to three fields:

```json
{
  "action": "RELEASE",
  "outcome": "RELEASED",
  "mutationAttempted": true
}
```

| Outcome family | HTTP | Automatic retry |
|---|---:|---|
| `RELEASED` | 200 | No |
| `INVALID_LOCK_NAME` | 400 | No |
| `NOT_REGISTERED` | 404 | No |
| `AMBIGUOUS`, `NOT_HELD`, `ACTION_IN_PROGRESS` | 409 | No |
| `ACTION_ADMISSION_REJECTED` | 429 | No |
| `OWNERSHIP_UNKNOWN`, `RELEASE_UNCONFIRMED`, `RELEASE_FAILED`, `REGISTRY_CLOSED` | 503 | No |
| `ACTION_TIMED_OUT` | 504 | No |

`ACTION_TIMED_OUT` reports whether release had started. Do not retry it until the
worker has terminalized. Never promote `RELEASE_UNCONFIRMED` or `RELEASE_FAILED` to
success. Invalid selectors are rejected before registry mutation.

## Safe rollout and rollback

1. Leave the parent or nested property disabled while deploying the code.
2. Confirm that `leaderElectionActions` is absent from HTTP exposure in a canary.
3. Enable the nested property only with an authenticated internal Actuator policy.
4. Exercise one known lock and verify the allow-listed body and outcome metric.
5. If the action path or outcomes are unexpected, remove it from HTTP exposure and
   set `actions.enabled=false`; do not use repeated release requests as recovery.
6. Inspect the core outcome, quarantine count, and backend diagnostics before deciding
   whether a lease is still owned.

Logs and metrics must remain sanitized. Do not add lock names, actor or credential
values, backend payloads, tokens, or exception text to an endpoint response or
management log.

## Promotion gate

This draft can move into the versioned manual only when the release manifest points to
a commit containing the action API, the Spring HTTP and ABI tests pass at that exact
commit, and the deployment runbook has an application-owned authentication policy.

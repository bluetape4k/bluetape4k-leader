---
title: "Management action operations and quarantine runbook"
locale: en
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Management action operations and quarantine runbook

> Unreleased draft for Issue #532. It is intentionally outside the pinned `0.5.0` manual because the management-action contract is newer than `sourceReleaseCommit`. Promote it with the API drafts only after the next release manifest is updated.

## Operating boundary

Management actions are bounded, process-local lease cleanup controls. They are useful
when an operator has evidence that one instance must relinquish a lease, but they do
not prove that a different instance should take ownership. Register only a concrete
single-leader lease handle. Do not use the surface for group/semaphore or strategic
election, route-runtime leases, scheduled jobs, force unlock, rename, or conversion.

The application owns authentication, authorization, registry scope, and the decision
to expose an HTTP adapter. A registry admission is closed before shutdown drain; a
registration token is a reference, not a lease release command.

## Outcome and HTTP matrix

Both Spring and Ktor adapters use the same framework-neutral mapping:

| Core outcome | HTTP | `mutationAttempted` | Retry |
|---|---:|:---:|---|
| `RELEASED` | 200 | true | Never automatic |
| `INVALID_LOCK_NAME` | 400 | false | Fix selector |
| `NOT_REGISTERED` | 404 | false | Do not repeat blindly |
| `AMBIGUOUS` | 409 | false | Remove duplicate registration |
| `NOT_HELD` | 409 | false | Re-read ownership |
| `ACTION_IN_PROGRESS` | 409 | false | Wait for the existing action |
| `ACTION_ADMISSION_REJECTED` | 429 | false | Back off and inspect capacity |
| `OWNERSHIP_UNKNOWN` | 503 | false | Inspect backend/lease state |
| `RELEASE_UNCONFIRMED` | 503 | true | Stop; do not promote to success |
| `RELEASE_FAILED` | 503 | true | Stop; inspect backend and quarantine |
| `REGISTRY_CLOSED` | 503 | false | Reconfigure lifecycle |
| `ACTION_TIMED_OUT` | 504 | false or true | Wait for terminalization; never automatic |

The response body is intentionally small: action, outcome, and mutation flag only.
`retryAllowed` is always false. A timeout after release begins can still mutate the
backend; the flag is evidence of that uncertainty, not permission to issue another
release.

## Quarantine signals

The core registry preserves an admitted worker's capacity until cleanup really ends.
It may quarantine a reservation for cleanup timeout, non-interruptible callback,
callback error, or close timeout. Observations and Micrometer metrics use fixed,
low-cardinality phase/reason/surface values. They never contain a lock name, actor,
credential, request ID, token, backend payload, or exception message.

Watch the following signals together:

- quarantine observations by fixed phase and surface;
- quarantine counter by fixed reason;
- active/quarantined gauge returning to zero only after the worker terminates;
- backend diagnostics and application shutdown/drain result.

Do not infer ownership from stale status reference data or from a successful connectivity
probe. Reconcile with the backend's conditional ownership semantics.

## Disable and rollback runbook

1. Disable the nested action property or remove `leaderElectionActions`/the Ktor route
   from HTTP exposure. Keep the parent read-only status endpoint unchanged.
2. Confirm the write endpoint is absent with an unauthenticated canary and a permitted
   internal canary. A route that returns 401/403 is still installed; absence is 404.
3. Verify the authentication and network policy before any further action. Never place
   credentials or request details in a ticket, log line, metric tag, or curl history.
4. Observe typed outcomes, quarantine counters, active gauge recovery, and backend
   diagnostics for the affected deployment window.
5. If an outcome is `RELEASE_UNCONFIRMED`, `RELEASE_FAILED`, or a timeout with
   `mutationAttempted=true`, stop retries and treat ownership as unknown until the
   worker/quarantine and backend state have converged.
6. Drain and replace the registry or instance using the application's lifecycle
   coordinator. A failed drain must not cancel an unrelated application scope.
7. Re-enable the action only after a bounded canary, a documented authorization rule,
   and a rollback switch are in place.

## Shutdown ordering

Call the adapter's graceful shutdown helper before stopping the engine/context. It
closes admission, waits for admitted workers within the configured bound, logs a
sanitized warning on a false drain result, and continues the engine stop. It never
releases arbitrary leases, installs a global shutdown listener, or cancels a caller's
application scope.

## Promotion gate

This runbook is ready for the versioned manual only when the Spring and Ktor drafts,
the exact-release test evidence, the sanitized metrics/log review, and the release
manifest provenance all agree. Until then, treat every management-action instruction
as unreleased operational guidance.

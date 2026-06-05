# Preview Backend Stable Promotion Checklist

This checklist defines the evidence required before changing a preview backend
row in `README.md` / `README.ko.md` to `Stable`.

It complements `docs/release/preview-backend-nightly-gate.md`. A green full
Nightly run is required release evidence, but it is not enough by itself to
promote a backend to stable.

## Scope

Current preview backends:

| Backend | Module | Storage model |
|---|---|---|
| Consul | `leader-consul` | Consul Session + KV acquire/release |
| DynamoDB | `leader-dynamodb` | Conditional writes + logical TTL |
| etcd | `leader-etcd` | etcd v3 Lock service + leases |
| Kubernetes Lease | `leader-k8s` | `coordination.k8s.io/v1` Lease |

Promotion is backend-specific. Do not promote all preview backends together
unless each row below is independently satisfied.

## Shared Promotion Bar

| Area | Required evidence | Blocks promotion when |
|---|---|---|
| Runtime contracts | Single-leader and group-leader behavior is covered by focused tests, including contention, reacquire, release, timeout, and cleanup paths. | Any split-brain, stale-owner, lost-release, or cleanup timeout remains unresolved. |
| API stability | Public options, endpoint/configuration types, auto-configuration properties, and KDoc have no planned breaking rename for the next minor line. | A backend still exposes third-party implementation details that should be hidden behind bluetape4k-owned DTOs or properties. |
| Cancellation and lifecycle | Blocking, async, suspend, and virtual-thread paths supported by the backend have equivalent timeout/cancellation semantics. | A supported execution model leaks resources, hides cancellation, or depends on caller-side sleep/reaper behavior. |
| CI and Nightly | PR CI module test passes, and the latest full `Nightly` preview backend release summary reports `success` for the backend. | The backend is skipped in full Nightly, has only a fast smoke result, or has a recent unresolved flake/failure. |
| Documentation | README locale set, KDoc, configuration docs, release notes, and known limitations all match the implemented contract. | README says Stable while docs still mention preview-only caveats or missing setup requirements. |
| Examples | At least one runnable adoption example exists when the backend has non-trivial setup or operational semantics. | Users must infer required infrastructure, credentials, TTL, or lease behavior only from tests. |
| Benchmark or operational evidence | Existing benchmark rows or operational notes explain relative cost, noise, and unsupported comparisons. | Performance docs invite unsupported comparisons or omit known noisy rows. |
| Release governance | The promotion PR links all evidence and closes or creates follow-up issues for remaining non-blocking work. | Promotion depends on chat-only evidence, stale local runs, or hidden follow-up work. |

## Backend Checklist

### Consul

Required evidence:

- `Test / leader-consul` succeeds in PR CI when Consul paths change.
- Full `Nightly` `Preview Backend Release Summary` reports `success` for
  `Test / leader-consul`.
- Session/KV acquire, release, ownership read, timeout, and cleanup tests cover
  blocking, async, and suspend paths.
- Spring Boot auto-configuration docs and properties do not expose unstable
  third-party implementation details as the public contract.
- `examples/consul-maintenance` remains runnable and documented in both locale
  README files.

Current 0.4.0 blocker check:

- No separate Consul example gap is open.
- Before promotion, run an API-stability review against `leader-consul` public
  endpoint/options/configuration types and link the review result in the PR.

### DynamoDB

Required evidence:

- `Test / leader-dynamodb` succeeds in PR CI when DynamoDB paths change.
- Full `Nightly` `Preview Backend Release Summary` reports `success` for
  `Test / leader-dynamodb (DynamoDB Local)`.
- Conditional-write ownership, logical TTL, stale item replacement, timeout,
  and cleanup semantics are covered for supported execution models.
- DynamoDB Local setup, AWS region/credential requirements, table/TTL behavior,
  and Spring Boot properties are documented.
- A runnable DynamoDB adoption example exists.

Current 0.4.0 blocker check:

- `#423` is still open for the DynamoDB scheduled-export leader example. Keep
  DynamoDB as Preview until that example or an explicitly equivalent adoption
  path is merged.

### etcd

Required evidence:

- `Test / leader-etcd` succeeds in PR CI when etcd paths change.
- Full `Nightly` `Preview Backend Release Summary` reports `success` for
  `Test / leader-etcd (Testcontainers)`.
- Lease grant/keepalive/revoke, Lock service ownership, cleanup timeout, and
  richer ownership metadata are covered by tests and docs.
- `examples/etcd-reconciler` remains runnable and documented in both locale
  README files.
- Known etcd client/version constraints are documented before the README row is
  changed to Stable.

Current 0.4.0 blocker check:

- No separate etcd example gap is open.
- Before promotion, rerun the etcd cleanup/metadata test slice and link the
  result with the full Nightly summary.

### Kubernetes Lease

Required evidence:

- `Test / leader-k8s` succeeds in PR CI for the unit/smoke slice.
- Full `Nightly` `Preview Backend Release Summary` reports `success` for
  `Test / leader-k8s (K3s + group slots)`.
- Lease acquire/update/release, group slot behavior, namespace/name validation,
  and K3s runtime coverage are all green after any Fabric8/Vert.x runtime
  dependency change.
- `examples/k8s-lease` and `examples/k8s-operator` remain runnable and
  documented in both locale README files.
- Kubernetes RBAC, namespace, and K3s/Testcontainers requirements are stated in
  user-facing docs.

Current 0.4.0 blocker check:

- `#480` closed the Fabric8 Vert.x runtime failure, and the latest full Nightly
  after that fix succeeded on 2026-06-04:
  https://github.com/bluetape4k/bluetape4k-leader/actions/runs/26984503181
- Treat any later K3s, Fabric8, or Vert.x runtime regression as promotion
  blocking until a newer full Nightly succeeds.

## Promotion PR Rules

Use a dedicated promotion PR for each backend unless the user explicitly asks
for a combined release-prep PR.

Each promotion PR must include:

1. `README.md` and `README.ko.md` status row update from `Preview` to `Stable`.
2. Links to the latest full Nightly summary and any targeted local validation.
3. API/KDoc review result with P0=0 and P1=0.
4. Example/docs evidence or linked follow-up issue when the missing example is
   intentionally not release-blocking.
5. Release note or changelog entry explaining the promotion and remaining
   limitations.

Do not change a backend status row to `Stable` in a drive-by docs PR.

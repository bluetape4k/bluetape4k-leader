# Preview Backend Nightly Release Gate

This note defines the release preflight evidence for preview leader backends.

## Required Summary

Before tagging a stable release, open the latest full `Nightly` workflow run on
`develop` and read the `Preview Backend Release Summary` job summary.

The release-ready state is:

| Backend | Evidence job | Required result |
|---|---|---|
| Consul | `Test / leader-consul` | `success` |
| DynamoDB | `Test / leader-dynamodb (DynamoDB Local)` | `success` |
| etcd | `Test / leader-etcd (Testcontainers)` | `success` |
| Kubernetes Lease | `Test / leader-k8s (K3s + group slots)` | `success` |

`skipped` means the run was not a full release-validation Nightly run. Trigger
`Nightly` with the full scope, or wait for the weekly scheduled full run, before
using it as release evidence.

## Scope Boundary

Daily CI remains a smoke and targeted-change gate. Testcontainers-heavy and K3s
runtime checks stay in full Nightly so release evidence is complete without
slowing every pull request.

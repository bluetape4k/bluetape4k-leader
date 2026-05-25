# Issue 376 preview backend release gate

## Context

Milestone 0.2.2 promotes the preview Consul, DynamoDB, etcd, and Kubernetes
Lease backends. The release gate needed to show which runtime-backed test lanes
protect the patch release and why Kubernetes differs from the other preview
backends.

## Decision

Keep PR CI check names stable, but document the gate directly in workflow
comments. Consul, DynamoDB Local, and etcd run their module `test` tasks in both
PR CI and Nightly. Kubernetes keeps the default `test` task in PR CI, while the
privileged K3s `k8sTest` task remains in Nightly full.

Include the K3s Nightly full job in the Nightly coverage and final status
aggregation so a K3s failure fails the full release gate.

Consul integration tests use `ConsulServer.Launcher.consul`. DynamoDB Local and
etcd already used the standard Testcontainers CI environment, so the Consul CI
and Nightly jobs now use the same `TESTCONTAINERS_RYUK_DISABLED`, `DOCKER_HOST`,
and 4 GiB Gradle heap settings.

## Outcome

The 0.2.2 preview backend gate is explicit in `.github/workflows/ci.yml` and
`.github/workflows/nightly-tests.yml`. K3s is not part of the fast PR runtime
gate because it requires privileged Docker/K3s behavior; it is covered by the
existing Nightly full path instead.

## Verification

- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`
- `./gradlew :bluetape4k-leader-consul:test :bluetape4k-leader-dynamodb:test :bluetape4k-leader-etcd:test --no-daemon`
- K3s runtime verification is delegated to Nightly full because local and fast
  PR lanes cannot assume privileged Docker/K3s availability.

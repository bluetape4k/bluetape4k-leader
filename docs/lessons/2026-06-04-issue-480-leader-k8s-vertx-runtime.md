# Issue 480 Leader K8s Vert.x Runtime

## Context

Post-merge Nightly cleared the Central snapshot metadata failures, but the
Leader K8s K3s job still failed all tests with
`NoSuchMethodError: WebClientOptions.setMaxPoolSize(int)`.

## Decision

Keep the repository's default Vert.x 5 line intact and isolate only
`leader-k8s` test runtime to Fabric8-compatible Vert.x 4.5.27 and Netty
4.1.133.Final. This mirrors the benchmark module's Kubernetes runtime isolation
instead of downgrading other preview backends.

## Outcome

The K3s test runtime should now use the Vert.x 4 HTTP client API expected by
Fabric8 Kubernetes client 7.7.x.

## Verification

- `./gradlew :bluetape4k-leader-k8s:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-web-client --no-daemon` selected `io.vertx:vertx-web-client:4.5.27`.
- `./gradlew :bluetape4k-leader-k8s:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-core --no-daemon` selected `io.vertx:vertx-core:4.5.27`.
- `./gradlew :bluetape4k-leader-k8s:dependencyInsight --configuration testRuntimeClasspath --dependency io.netty:netty-common --no-daemon` selected `io.netty:netty-common:4.1.133.Final`.
- `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --no-configuration-cache` passed with 20 K3s tests on 2026-06-04.

## Future Guard

When Fabric8 and etcd can share the same Vert.x line, remove this local runtime
pin together with the benchmark Kubernetes pin. Until then, do not rely on the
repo-wide Vert.x version for K3s-backed tests.

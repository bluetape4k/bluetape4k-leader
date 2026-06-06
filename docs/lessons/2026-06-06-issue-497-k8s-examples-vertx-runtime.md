# 2026-06-06 - Issue 497 K8s Examples Vert.x Runtime

## Context

Post-merge Examples workflow run 27054353270 still failed in the
`examples-k8s-lease` and `examples-k8s-operator` jobs with
`NoSuchMethodError: WebClientOptions.setMaxPoolSize(int)`. Issue #480 had fixed
the `leader-k8s` module runtime, but the example modules kept resolving Fabric8
Vert.x 4 requests to the repo-wide Vert.x 5 line.

## Decision

Apply the same Fabric8-compatible test runtime isolation to the two K3s-backed
examples: Vert.x 4.5.27 and Netty 4.1.133.Final on `testRuntimeClasspath`.
Keep the pin scoped to the examples instead of downgrading the repository-wide
Vert.x default used by non-Kubernetes preview backends.

## Outcome

The example K3s tests now execute with the Vert.x 4 HTTP client API expected by
Fabric8 Kubernetes client 7.7.x, matching the already-fixed `leader-k8s`
runtime shape.

## Verification

- `./gradlew :examples:k8s-lease:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-web-client --no-daemon` selected `io.vertx:vertx-web-client:4.5.27`.
- `./gradlew :examples:k8s-operator:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-web-client --no-daemon` selected `io.vertx:vertx-web-client:4.5.27`.
- `./gradlew :examples:k8s-lease:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-core --no-daemon` selected `io.vertx:vertx-core:4.5.27`.
- `./gradlew :examples:k8s-operator:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-core --no-daemon` selected `io.vertx:vertx-core:4.5.27`.
- `./gradlew :examples:k8s-lease:dependencyInsight --configuration testRuntimeClasspath --dependency io.netty:netty-common --no-daemon` selected `io.netty:netty-common:4.1.133.Final`.
- `./gradlew :examples:k8s-operator:dependencyInsight --configuration testRuntimeClasspath --dependency io.netty:netty-common --no-daemon` selected `io.netty:netty-common:4.1.133.Final`.
- `./gradlew :examples:k8s-lease:k8sTest --no-daemon --no-configuration-cache` passed with one K3s test on 2026-06-06.
- `./gradlew :examples:k8s-operator:k8sTest --no-daemon --no-configuration-cache` passed with two K3s tests on 2026-06-06.

## Future Guidance

When a runtime pin is needed for `leader-k8s`, check K3s-backed examples in the
same pass. The Examples workflow runs those modules independently, so module
tests can pass while example runtime classpaths still drift.

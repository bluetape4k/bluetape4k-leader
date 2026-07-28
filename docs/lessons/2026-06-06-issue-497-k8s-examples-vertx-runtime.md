# 2026-06-06 - 문제 497 K8s 예제 Vert.x 런타임

## 맥락

병합 후 예제 워크플로 실행 27054353270이 `NoSuchMethodError: WebClientOptions.setMaxPoolSize(int)`를 사용한 `examples-k8s-lease` 및 `examples-k8s-operator` 작업에서 여전히 failure했습니다. Issue #480은 `leader-k8s` 모듈 런타임을 수정했지만 예제 모듈은 저장소 전체 Vert.x 5 라인에 대한 Fabric8 Vert.x 4 요청을 계속 해결했습니다.

## 결정

`testRuntimeClasspath`의 Vert.x 4.5.27 및 Netty 4.1.133.Final이라는 두 개의 K3s 지원 예제에 동일한 Fabric8 호환 테스트 런타임 격리를 적용합니다. Kubernetes가 아닌 미리보기 백엔드에서 사용되는 저장소 전체 Vert.x 기본값을 다운그레이드하는 대신 핀 범위를 예제로 유지하세요.

## 결과

예제 K3s 테스트는 이제 이미 수정된 `leader-k8s` 런타임 형태와 일치하는 Fabric8 Kubernetes 클라이언트 7.7.x에서 예상되는 Vert.x 4 HTTP 클라이언트 API를 사용하여 실행됩니다.

## 검증

- `./gradlew :examples:k8s-lease:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-web-client --no-daemon`가 `io.vertx:vertx-web-client:4.5.27`를 선택했습니다.
- `./gradlew :examples:k8s-operator:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-web-client --no-daemon`가 `io.vertx:vertx-web-client:4.5.27`를 선택했습니다.
- `./gradlew :examples:k8s-lease:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-core --no-daemon`가 `io.vertx:vertx-core:4.5.27`를 선택했습니다.
- `./gradlew :examples:k8s-operator:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-core --no-daemon`가 `io.vertx:vertx-core:4.5.27`를 선택했습니다.
- `./gradlew :examples:k8s-lease:dependencyInsight --configuration testRuntimeClasspath --dependency io.netty:netty-common --no-daemon`가 `io.netty:netty-common:4.1.133.Final`를 선택했습니다.
- `./gradlew :examples:k8s-operator:dependencyInsight --configuration testRuntimeClasspath --dependency io.netty:netty-common --no-daemon`가 `io.netty:netty-common:4.1.133.Final`를 선택했습니다.
- `./gradlew :examples:k8s-lease:k8sTest --no-daemon --no-configuration-cache`는 2026년 6월 6일에 한 번의 K3s 테스트로 통과되었습니다.
- `./gradlew :examples:k8s-operator:k8sTest --no-daemon --no-configuration-cache`는 2026년 6월 6일에 두 가지 K3s 테스트를 통과했습니다.

## 향후 지침

`leader-k8s`에 런타임 핀이 필요한 경우 동일한 패스에서 K3s 지원 예제를 검증하세요. 예제 워크플로는 해당 모듈을 독립적으로 실행하므로 예제 런타임 클래스 경로가 계속 드리프트하는 동안 모듈 테스트를 통과할 수 있습니다.

# 이슈 480 리더 K8s Vert.x 런타임

## 맥락

병합 후 Nightly는 중앙 스냅샷 메타데이터 오류를 지웠지만 Leader K8s K3s 작업은 여전히 `NoSuchMethodError: WebClientOptions.setMaxPoolSize(int)`에 대한 모든 테스트에 failure했습니다.

## 결정

저장소의 기본 Vert.x 5 라인을 그대로 유지하고 `leader-k8s` 테스트 런타임만 Fabric8 호환 Vert.x 4.5.27 및 Netty 4.1.133.Final로 격리합니다. 이는 다른 미리보기 백엔드를 다운그레이드하는 대신 벤치마크 모듈의 Kubernetes 런타임 격리를 반영합니다.

## 결과

이제 K3s 테스트 런타임은 Fabric8 Kubernetes 클라이언트 7.7.x에서 예상되는 Vert.x 4 HTTP 클라이언트 API를 사용해야 합니다.

## 검증

- `./gradlew :bluetape4k-leader-k8s:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-web-client --no-daemon`가 `io.vertx:vertx-web-client:4.5.27`를 선택했습니다.
- `./gradlew :bluetape4k-leader-k8s:dependencyInsight --configuration testRuntimeClasspath --dependency io.vertx:vertx-core --no-daemon`가 `io.vertx:vertx-core:4.5.27`를 선택했습니다.
- `./gradlew :bluetape4k-leader-k8s:dependencyInsight --configuration testRuntimeClasspath --dependency io.netty:netty-common --no-daemon`가 `io.netty:netty-common:4.1.133.Final`를 선택했습니다.
- `./gradlew :bluetape4k-leader-k8s:k8sTest --no-daemon --no-configuration-cache`는 2026년 6월 4일에 20개의 K3s 테스트를 통과했습니다.

## 퓨쳐 가드

Fabric8 및 etcd가 동일한 Vert.x 라인을 공유할 수 있는 경우 벤치마크 Kubernetes 핀과 함께 이 로컬 런타임 핀을 제거하십시오. 그때까지는 K3s 지원 테스트를 위해 저장소 전체 Vert.x 버전에 의존하지 마십시오.

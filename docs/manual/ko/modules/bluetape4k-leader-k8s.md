---
manualId: "bluetape4k-leader-k8s"
id: "bluetape4k-leader-k8s"
title: "Kubernetes Lease 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-k8s"
sourceDir: "leader-k8s"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-k8s
---

# Kubernetes Lease 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

> **프리뷰:** 운영에 적용하기 전에 API와 운영 동작을 직접 검증하세요.

`coordination.k8s.io/v1` Lease 객체로 단일 선출과 고정 슬롯 그룹 선출을 구현한 프리뷰 백엔드입니다. 블로킹, future, 코루틴 API를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

Kubernetes에서 실행하는 workload가 별도 조율 저장소 대신 namespace와 RBAC를 그대로 활용하려 할 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-k8s`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-k8s")
}
```

## 핵심 개념 {#concepts}

획득할 때마다 고유 fencing token을 Lease holder identity에 기록합니다. Update, extend, release는 resource version과 owner를 조건으로 수행하며 Fabric8 client는 호출자가 소유합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = KubernetesLeaseLeaderElector(
    client,
    KubernetesLeaseOptions(namespace = "operators")
)
elector.runIfLeader("reconcile") { reconcile() }
```

## 작업별 API {#api-by-task}

`KubernetesLeaseLeaderElector`, suspend, group 구현을 사용합니다. Client 확장 함수로 블로킹과 future 호출을 짧게 만들 수 있습니다.

## 권장 패턴 {#patterns}

한 namespace의 Lease에 get/create/update 최소 권한만 부여합니다. 이름은 결정적으로 만들고 보호할 본문은 멱등하게 유지하세요.

## 연동 {#integrations}

Spring은 client로 factory를 만들 수 있습니다. k8s-lease와 k8s-operator 예제에서 scheduled job과 control loop를 볼 수 있습니다.

## 설정 {#configuration}

namespace, name prefix, wait/lease/minimum lease, retry delay, group size를 설정합니다. Client endpoint, 인증, 생명주기는 애플리케이션 책임입니다.

## 실패 유형과 해결 방법 {#failures}

경쟁과 budget 안의 resource-version 충돌은 skip/retry입니다. RBAC 거부, API 장애, 잘못된 Lease 상태, cleanup 실패는 예외로 드러납니다.

## 운영 {#operations}

Kubernetes API 지연, 409 충돌, 403 오류, renewal age, stale Lease, client throttle을 관측하세요. 진단 정보에는 namespace와 Lease 이름을 넣습니다.

## 테스트 {#testing}

실제 API server나 동작이 충실한 환경에서 두 client 충돌, resource-version race, 만료, owner-safe release, 그룹 슬롯, 취소를 검증합니다.

## 학습 경로와 예제 {#workshops}

k8s-lease를 먼저 실행하고 k8s-operator로 이어가세요. Kubernetes API와 etcd 직접 접근을 같은 것으로 보지 말고 운영 경계를 비교합니다.

## 제약 사항 {#limitations}

프리뷰 API는 바뀔 수 있습니다. Kubernetes API 가용성, RBAC, rate limit이 실행 경로에 들어오며 Lease가 외부 DB를 fence하지는 않습니다.

## 근거 자료 {#sources}

[Elector](../../../../leader-k8s/src/main/kotlin/io/bluetape4k/leader/k8s/KubernetesLeaseLeaderElector.kt) · [옵션](../../../../leader-k8s/src/main/kotlin/io/bluetape4k/leader/k8s/KubernetesLeaseOptions.kt) · [안정판 안내](../../../../leader-k8s/README.ko.md)


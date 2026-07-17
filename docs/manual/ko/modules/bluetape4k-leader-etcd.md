---
manualId: "bluetape4k-leader-etcd"
id: "bluetape4k-leader-etcd"
title: "etcd 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-etcd"
sourceDir: "leader-etcd"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-etcd
---

# etcd 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

> **프리뷰:** 운영에 적용하기 전에 API와 운영 동작을 직접 검증하세요.

etcd v3 lease와 jetcd Lock service로 단일·그룹 선출을 구현한 프리뷰 백엔드입니다. 블로킹, future, 코루틴, 가상 스레드 경로를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

etcd를 안정적인 control-plane 의존성으로 이미 운영하고 lease 의미가 workload에 맞을 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-etcd`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-etcd")
}
```

## 핵심 개념 {#concepts}

acquire 과정에서 lease와 lock key를 만들고 반환된 token으로 owner-safe release와 extension을 수행합니다. jetcd client는 호출자가 소유합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = EtcdLeaderElector(
    client,
    EtcdLeaderElectionOptions(keyPrefix = "/apps/orders/leader")
)
elector.runIfLeader("reconcile") { reconcile() }
```

## 작업별 API {#api-by-task}

`EtcdLeaderElector`, suspend, 가상 스레드, 그룹 구현을 호출 방식에 맞춰 고릅니다. Factory는 Core와 framework 통합에 사용합니다.

## 권장 패턴 {#patterns}

전용 prefix와 최소 권한 credential을 사용하세요. 긴 pause나 partition 중 lease를 잃을 수 있으므로 본문은 멱등하게 만듭니다.

## 연동 {#integrations}

Spring은 client로 factory를 만들 수 있습니다. etcd-reconciler 예제가 control loop workload를 보여 줍니다.

## 설정 {#configuration}

호출자 소유 client의 endpoint, key prefix, wait/lease/minimum lease, retry delay, cleanup timeout 예산을 설정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 skip입니다. Lease grant, keepalive, lock, cleanup timeout, 인증, transport 실패는 분류 후 예외로 전달됩니다.

## 운영 {#operations}

lease keepalive, 요청 지연, compaction/cluster 상태, cleanup 실패, skip 작업을 관측하세요. Client 종료는 애플리케이션 책임입니다.

## 테스트 {#testing}

실제 etcd container에서 acquire/release, lease 만료, key encoding, cleanup timeout, 그룹 슬롯, 취소를 검증합니다.

## 학습 경로와 예제 {#workshops}

etcd-reconciler를 실행한 뒤 etcd lease를 Consul session 및 Kubernetes Lease와 비교하세요.

## 제약 사항 {#limitations}

프리뷰 API는 바뀔 수 있습니다. etcd 가용성이 job 경로에 들어오며 lease가 업무 측 fencing token을 대신하지는 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader-etcd 아키텍처

[![leader-etcd 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-etcd-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-etcd-architecture-01.svg)

_배포본 README: [`leader-etcd/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-etcd/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderElector.kt) · [옵션](../../../../leader-etcd/src/main/kotlin/io/bluetape4k/leader/etcd/EtcdLeaderElectionOptions.kt) · [안정판 안내](../../../../leader-etcd/README.ko.md)


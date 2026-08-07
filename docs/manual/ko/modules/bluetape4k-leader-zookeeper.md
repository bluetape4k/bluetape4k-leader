---
manualId: "bluetape4k-leader-zookeeper"
id: "bluetape4k-leader-zookeeper"
title: "ZooKeeper 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-zookeeper"
sourceDir: "leader-zookeeper"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-zookeeper
---

# ZooKeeper 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Apache Curator `InterProcessMutex`와 `InterProcessSemaphoreV2`로 단일·그룹 선출을 구현합니다. 블로킹, future, 코루틴 API를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

ZooKeeper/Curator를 지원되는 조율 계층으로 이미 운영하고 session 기반 소유권을 이해할 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-zookeeper`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-zookeeper")
}
```

## 핵심 개념 {#concepts}

ZooKeeper session이 만료되면 ephemeral recipe node가 사라집니다. `leaseTime`은 Core API 일관성을 위해 받지만 실제 해제 경계가 아닙니다. Suspend 단일 선출은 Curator의 acquire/release owner-thread 제약을 지킵니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val curator = CuratorFrameworkFactory.newClient(connect, retry).apply { start() }
val elector = ZooKeeperLeaderElector(curator)
elector.runIfLeader("daily-report") { generateReport() }
```

## 작업별 API {#api-by-task}

단일/group blocking elector, async 메서드, suspend 구현, client 확장 함수, factory를 사용합니다. Curator client는 호출자가 소유합니다.

## 권장 패턴 {#patterns}

전용 base path, 안정적인 session 설정, 제한된 retry, 멱등 본문을 사용합니다. Session suspend/loss는 소유권 이벤트로 취급하세요.

## 연동 {#integrations}

Spring은 factory를 사용할 수 있습니다. zookeeper-scheduler 예제에 예약 작업과 client 생명주기가 나와 있습니다.

## 설정 {#configuration}

base path, wait time, group size, Curator connection/session timeout, retry, ACL, ensemble endpoint를 설정합니다. Core `leaseTime`을 ZooKeeper TTL로 해석하면 안 됩니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 skip입니다. Connection suspend/loss, session 만료, ACL, retry 소진, Curator recipe 실패는 예외로 드러납니다. Session을 잃으면 본문과 무관하게 소유권이 해제됩니다.

## 운영 {#operations}

connection 상태, session 만료, ensemble 지연, retry, znode 증가, group permit, skip을 관측합니다. Curator는 애플리케이션 종료 때만 닫으세요.

## 테스트 {#testing}

실제 ZooKeeper에서 session loss, owner-thread release, 두 client 경쟁, group permit, extension 의미, async 완료, coroutine 취소를 검증합니다.

## 학습 경로와 예제 {#workshops}

zookeeper-scheduler를 실행한 뒤 session 기반 소유권을 TTL 기반 Redis 및 lease 기반 etcd와 비교하세요.

## 제약 사항 {#limitations}

`leaseTime` 시점에 고정적으로 해제된다는 보장은 없고 session timeout이 결정합니다. ZooKeeper 소유권도 외부 부수 효과를 fence하지 못합니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader zookeeper 클래스 구조도

[![leader zookeeper 클래스 구조도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-zookeeper-class-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-zookeeper-class-01.svg)

_배포본 README: [`leader-zookeeper/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-zookeeper/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperLeaderElector.kt) · [Suspend elector](../../../../leader-zookeeper/src/main/kotlin/io/bluetape4k/leader/zookeeper/ZooKeeperSuspendLeaderElector.kt) · [안정판 안내](../../../../leader-zookeeper/README.ko.md)


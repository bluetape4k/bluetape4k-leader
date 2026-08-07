---
manualId: "bluetape4k-leader-hazelcast"
id: "bluetape4k-leader-hazelcast"
title: "Hazelcast 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-hazelcast"
sourceDir: "leader-hazelcast"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-hazelcast
---

# Hazelcast 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Hazelcast `IMap`의 TTL entry와 owner token으로 단일 선출과 고정 슬롯 그룹 선출을 구현합니다. 블로킹, future, 코루틴 경로 모두 Core의 skip-on-contention 계약을 따릅니다.

## 사용하기 좋은 경우 {#when-to-use}

애플리케이션이 Hazelcast cluster를 이미 운영하고 같은 장애 영역에서 조율하려 할 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-hazelcast`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-hazelcast")
}
```

## 핵심 개념 {#concepts}

`putIfAbsent`와 TTL로 token 소유 entry를 얻습니다. 락은 thread에 묶이지 않지만 extend와 release는 token을 확인해 이전 holder가 새 lease를 건드리지 못하게 해야 합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = HazelcastLeaderElector(
    hazelcastInstance,
    LeaderElectionOptions(leaseTime = 30.seconds)
)
elector.runIfLeader("cache-warmer") { warmCache() }
```

## 작업별 API {#api-by-task}

단일/group elector와 suspend 구현을 사용합니다. Factory는 framework와 연결됩니다. State API는 공유 map을 읽을 뿐 실행 권한을 주지 않습니다.

## 권장 패턴 {#patterns}

Hazelcast instance는 호출자가 소유하고 lock name은 안정적으로 유지합니다. 선출 map의 capacity를 확보하고 본문은 멱등하게 만드세요.

## 연동 {#integrations}

Spring factory는 `HazelcastInstance`를 사용할 수 있습니다. cache-warmer 예제에 일반적인 scheduled workload가 나와 있습니다.

## 설정 {#configuration}

wait, lease, minimum lease, group size, cluster discovery, split-brain protection, map backup 정책을 정합니다. 기본 단일 락 map은 `bluetape4k:leader:locks`입니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 `null`입니다. Cluster disconnect, serialization/config 오류, owner-safe extend/release 실패는 예외로 드러납니다. TTL 만료 뒤 pause된 이전 작업과 겹칠 수 있습니다.

## 운영 {#operations}

cluster membership, partition migration, map 지연, backup 상태, 연장 실패, skip 비율을 관측합니다.

## 테스트 {#testing}

여러 member를 띄워 경쟁, partition/membership 변화, TTL 만료, owner-token 해제, 그룹 슬롯, suspend 취소를 검증합니다.

## 학습 경로와 예제 {#workshops}

cache-warmer를 실행한 뒤 인메모리 분산 모델을 Redis와 MongoDB 백엔드와 비교하세요.

## 제약 사항 {#limitations}

Hazelcast 가용성과 split-brain 정책이 선출 의존성이 됩니다. TTL 소유권은 외부 fencing token이 아닙니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader-hazelcast implementation 구조도

[![leader-hazelcast implementation 구조도](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-class-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-class-01.svg)

_배포본 README: [`leader-hazelcast/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-hazelcast/README.ko.md)_

### Hazelcast lock acquire and release 시퀀스 다이어그램

[![Hazelcast lock acquire and release 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-02.svg)

_배포본 README: [`leader-hazelcast/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-hazelcast/README.ko.md)_

### Hazelcast group election slot 시퀀스 다이어그램

[![Hazelcast group election slot 시퀀스 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-03.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-hazelcast-sequence-03.svg)

_배포본 README: [`leader-hazelcast/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-hazelcast/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/HazelcastLeaderElector.kt) · [Lock 구현](../../../../leader-hazelcast/src/main/kotlin/io/bluetape4k/leader/hazelcast/lock/HazelcastLock.kt) · [안정판 안내](../../../../leader-hazelcast/README.ko.md)


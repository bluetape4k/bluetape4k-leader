---
manualId: "bluetape4k-leader-mongodb"
id: "bluetape4k-leader-mongodb"
title: "MongoDB 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-mongodb"
sourceDir: "leader-mongodb"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-mongodb
---

# MongoDB 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

token 소유 MongoDB document, 원자적 `findOneAndUpdate`, 만료 시각, TTL index로 단일·그룹 선출을 구현합니다. 블로킹과 코루틴 factory를 제공합니다.

## 사용하기 좋은 경우 {#when-to-use}

MongoDB가 이미 내구성 있는 애플리케이션 의존성이고 같은 replica set에서 조율하는 편이 운영상 단순할 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-mongodb`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-mongodb")
}
```

## 핵심 개념 {#concepts}

collection의 lock document에 owner token과 `expireAt`을 저장하고 원자적 filter로 acquire와 extend를 수행합니다. TTL index 정리는 정합성을 판단하는 시계가 아닙니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val collection = database.getCollection<Document>("bluetape4k_leader_locks")
val elector = MongoLeaderElector(collection)
elector.runIfLeader("tenant-aggregation") { aggregate() }
```

## 작업별 API {#api-by-task}

blocking/suspend 단일·그룹 elector, factory, 확장 함수, 선택적 Mongo history sink/indexer를 사용합니다. Collection 생명주기는 호출자 소유입니다.

## 권장 패턴 {#patterns}

Replica set에서는 majority write concern을 사용하세요. Collection/name prefix를 안정적으로 유지하고 본문은 멱등하게 만들며 extend/release에 owner token 조건을 둡니다.

## 연동 {#integrations}

Spring은 `MongoClient`로 factory를 만들 수 있습니다. tenant-aggregator 예제가 partitioned workload를 보여 줍니다.

## 설정 {#configuration}

database/collection, wait/lease/minimum lease, retry delay, group size, write concern, history TTL/index 정책을 설정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 `null`입니다. Network, primary election, write concern, 권한, index 오류는 예외로 드러납니다. TTL 삭제 지연으로 acquire를 판단하지 않습니다.

## 운영 {#operations}

command 지연, write concern 오류, primary 변경, retry, stale document, TTL monitor 지연, extension 실패, history 증가를 관측합니다.

## 테스트 {#testing}

Replica-set Testcontainer에서 두 client 경쟁, failover, TTL/expiry, 이전 owner, 그룹 슬롯, suspend 취소, index 생성을 검증합니다.

## 학습 경로와 예제 {#workshops}

tenant-aggregator를 실행한 뒤 document 기반 조율과 SQL transaction 기반 조율을 비교하세요.

## 제약 사항 {#limitations}

Replica-set 설정이 split-brain 위험에 영향을 줍니다. MongoDB lease는 다른 시스템의 쓰기를 fence하지 못합니다.

## 근거 자료 {#sources}

[Elector](../../../../leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/MongoLeaderElector.kt) · [Lock 구현](../../../../leader-mongodb/src/main/kotlin/io/bluetape4k/leader/mongodb/lock/MongoLock.kt) · [안정판 안내](../../../../leader-mongodb/README.ko.md)


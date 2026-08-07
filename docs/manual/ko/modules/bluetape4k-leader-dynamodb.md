---
manualId: "bluetape4k-leader-dynamodb"
id: "bluetape4k-leader-dynamodb"
title: "DynamoDB 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-dynamodb"
sourceDir: "leader-dynamodb"
releaseRef: "0.5.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-dynamodb
---

# DynamoDB 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

> **프리뷰:** 운영에 적용하기 전에 API와 운영 동작을 직접 검증하세요.

DynamoDB 조건부 쓰기와 논리 lease 만료를 사용하는 프리뷰 백엔드입니다. 블로킹, async, 가상 스레드, 코루틴, 그룹 선출을 지원합니다.

## 사용하기 좋은 경우 {#when-to-use}

이미 DynamoDB를 쓰는 AWS workload에서 별도 조율 서비스를 추가하지 않고 같은 region 안의 작업을 조율할 때 적합합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-dynamodb`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-dynamodb")
}
```

## 핵심 개념 {#concepts}

테이블의 문자열 partition key는 `lockName`입니다. 행에는 owner ID, 밀리초 `leaseExpiry`, 초 단위 `ttl`을 저장합니다. 정합성은 `leaseExpiry`가 결정하며 DynamoDB TTL은 정리용 메타데이터일 뿐입니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = DynamoDbLeaderElector(
    dynamoDbClient,
    DynamoDbLeaderElectionOptions(tableName = "bluetape4k_leader_locks")
)
elector.runIfLeader("export") { exportData() }
```

## 작업별 API {#api-by-task}

동기, async-client 기반 suspend, 가상 스레드, 그룹 구현 중에서 고릅니다. 테이블과 AWS client 생명주기는 애플리케이션이 소유합니다.

## 권장 패턴 {#patterns}

경쟁량에 맞춰 capacity를 잡고 안정적인 key prefix를 사용하세요. 본문은 멱등하게 만들며 extend/release는 owner 조건을 유지합니다.

## 연동 {#integrations}

Spring auto-configuration은 호출자 소유 AWS client를 사용할 수 있습니다. dynamodb-export 예제에 전체 job 생명주기가 나와 있습니다.

## 설정 {#configuration}

table 이름, key prefix, wait/lease/minimum lease, retry, TTL attribute를 설정합니다. 숫자형 `ttl`에 DynamoDB TTL을 켜 정리에 사용합니다.

## 실패 유형과 해결 방법 {#failures}

조건부 쓰기 경쟁은 skip입니다. SDK, IAM, throttling, network 실패는 예외로 드러납니다. TTL 삭제 지연으로 acquire 안전성을 판단하면 안 됩니다.

## 운영 {#operations}

consumed capacity, throttle, 조건 실패, 지연, stale row 수, 시계 차이를 관측하세요. 테이블 생명주기는 인프라 코드로 관리합니다.

## 테스트 {#testing}

DynamoDB Local/Testcontainers에서 두 client의 acquire, 만료, 이전 owner 해제 차단, 그룹 슬롯, suspend와 가상 스레드 경로를 검증합니다.

## 학습 경로와 예제 {#workshops}

dynamodb-export 예제를 실행하고 AWS 운영 모델을 Redis 또는 SQL 백엔드와 비교하세요.

## 제약 사항 {#limitations}

프리뷰이며 TTL 정리가 즉시 실행되지 않습니다. Cross-region 조율과 global table 충돌은 이 계약의 범위 밖입니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.5.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader-dynamodb 아키텍처

[![leader-dynamodb 아키텍처](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-dynamodb-architecture-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-dynamodb-architecture-01.svg)

_배포본 README: [`leader-dynamodb/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-dynamodb/README.ko.md)_

### leader-dynamodb conditional lease 처리 순서

[![leader-dynamodb conditional lease 처리 순서](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-dynamodb-sequence-02.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/docs/images/readme-diagrams/leader-dynamodb-sequence-02.svg)

_배포본 README: [`leader-dynamodb/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/721a9a3808f67489d2bdb8177734325981c24977/leader-dynamodb/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-dynamodb/src/main/kotlin/io/bluetape4k/leader/dynamodb/DynamoDbLeaderElector.kt) · [옵션](../../../../leader-dynamodb/src/main/kotlin/io/bluetape4k/leader/dynamodb/DynamoDbLeaderElectionOptions.kt) · [안정판 안내](../../../../leader-dynamodb/README.ko.md)


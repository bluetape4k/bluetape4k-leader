---
manualId: "bluetape4k-leader-exposed-jdbc"
id: "bluetape4k-leader-exposed-jdbc"
title: "Exposed JDBC 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-exposed-jdbc"
sourceDir: "leader-exposed-jdbc"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc
---

# Exposed JDBC 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Exposed JDBC 트랜잭션으로 단일 선출과 그룹 선출을 구현합니다. 블로킹 방식이나 가상 스레드를 사용하는 서비스에 맞습니다.

## 사용하기 좋은 경우 {#when-to-use}

운영 중인 관계형 DB에서 배치 작업까지 조정할 수 있고, 별도의 조정 서비스를 추가할 이유가 적을 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc")
}
```

## 핵심 개념 {#concepts}

조건부 행 갱신으로 리스를 얻습니다. 만료 시각과 소유권 토큰을 함께 확인하므로 이전 소유자가 새 리스를 해제할 수 없습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = ExposedJdbcLeaderElector(database)
elector.runIfLeader("invoice-close") { closeInvoices() }
```

## 작업별 API {#api-by-task}

작업에 따라 블로킹 API, 그룹 선출, 팩토리, 확장 함수, 이력 저장소, `ExposedJdbcVirtualThreadLeaderElector`를 사용합니다.

## 권장 패턴 {#patterns}

서비스 트래픽을 받기 전에 스키마를 만들고, 락 획득 트랜잭션은 짧게 유지합니다. 의도적으로 하나의 원자적 작업으로 묶은 경우가 아니라면 업무 트랜잭션과 분리하세요.

## 연동 {#integrations}

이 릴리스가 지원하는 Exposed JDBC 데이터베이스에서 동작합니다. Spring에서는 `Database` 빈으로 팩토리를 만들 수 있습니다.

## 설정 {#configuration}

대기 시간, 리스 시간, 최소 리스 시간, 재시도, 스키마, 격리 수준, 명령 제한 시간, 연결 풀 용량을 조정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁에서 밀리면 `null`을 반환합니다. 연결, 권한, 스키마, 롤백, 재시도 소진 문제는 예외로 드러납니다.

## 운영 {#operations}

DB 지연, 연결 풀 포화, 재시도, 정리 작업, 인덱스, 시계 일관성을 관찰합니다.

## 테스트 {#testing}

운영에서 사용하는 DB와 같은 방언의 Testcontainers 환경에서 두 연결의 경쟁, 만료, 이전 소유자의 해제 차단, 그룹 용량, 롤백을 검증합니다.

## 학습 경로와 예제 {#workshops}

`migration-gate`를 실행하고 R2DBC 방식과 비교한 뒤, Spring 배치 작업은 `batch-scheduler`에서 이어서 살펴보세요.

## 제약 사항 {#limitations}

모든 락 획득 요청이 DB를 거칩니다. 따로 설계하지 않으면 리스와 업무 처리가 하나의 트랜잭션으로 묶이지 않습니다.

## 근거 자료 {#sources}

[Elector](../../../../leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElector.kt) · [Initializer](../../../../leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcSchemaInitializer.kt) · [안정판 안내](../../../../leader-exposed-jdbc/README.ko.md)

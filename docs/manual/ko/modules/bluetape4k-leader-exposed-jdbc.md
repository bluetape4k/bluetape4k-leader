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

Exposed JDBC transaction으로 단일·그룹 선출을 구현합니다. 블로킹 또는 가상 스레드 서비스에 맞습니다.

## 사용하기 좋은 경우 {#when-to-use}

운영 중인 관계형 DB로 job까지 조율할 수 있고 별도 조율 서비스를 추가할 이유가 적을 때 선택합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc")
}
```

## 핵심 개념 {#concepts}

조건부 행 전이로 lease를 얻고 만료 시각과 owner token으로 이전 holder가 새 lease를 해제하지 못하게 합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = ExposedJdbcLeaderElector(database)
elector.runIfLeader("invoice-close") { closeInvoices() }
```

## 작업별 API {#api-by-task}

작업에 맞춰 blocking, group, factory, 확장 함수, history sink, `ExposedJdbcVirtualThreadLeaderElector`를 사용합니다.

## 권장 패턴 {#patterns}

트래픽 전에 스키마를 만들고 acquire transaction은 짧게 유지합니다. 의도적으로 원자화하지 않는 한 업무 transaction과 분리하세요.

## 연동 {#integrations}

이 릴리스가 지원하는 Exposed JDBC DB에서 동작합니다. Spring은 `Database` Bean으로 factory를 만들 수 있습니다.

## 설정 {#configuration}

wait, lease, minimum lease, retry, schema, isolation, statement timeout, pool 용량을 조정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 `null`입니다. 연결, 권한, 스키마, rollback, retry 소진 실패는 예외로 드러납니다.

## 운영 {#operations}

DB 지연, pool 포화, retry, cleanup, index, 시계 일관성을 관측합니다.

## 테스트 {#testing}

운영 dialect의 Testcontainers에서 두 연결, 만료, 이전 owner 해제 차단, 그룹 용량, rollback을 검증합니다.

## 학습 경로와 예제 {#workshops}

migration-gate를 실행하고 R2DBC와 비교한 뒤 Spring job은 batch-scheduler로 이어가세요.

## 제약 사항 {#limitations}

모든 acquire가 DB를 거칩니다. 따로 설계하지 않으면 lease와 업무가 하나의 transaction이 되지 않습니다.

## 근거 자료 {#sources}

[Elector](../../../../leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/ExposedJdbcLeaderElector.kt) · [Initializer](../../../../leader-exposed-jdbc/src/main/kotlin/io/bluetape4k/leader/exposed/jdbc/lock/ExposedJdbcSchemaInitializer.kt) · [안정판 안내](../../../../leader-exposed-jdbc/README.ko.md)


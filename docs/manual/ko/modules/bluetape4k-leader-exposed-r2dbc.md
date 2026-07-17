---
manualId: "bluetape4k-leader-exposed-r2dbc"
id: "bluetape4k-leader-exposed-r2dbc"
title: "Exposed R2DBC 백엔드"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-exposed-r2dbc"
sourceDir: "leader-exposed-r2dbc"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc
---

# Exposed R2DBC 백엔드

> 라이브러리 모듈

## 제공하는 기능 {#problem}

Exposed R2DBC transaction으로 코루틴 단일·그룹 선출을 구현하며 JDBC와 같은 SQL 소유권 규칙을 따릅니다.

## 사용하기 좋은 경우 {#when-to-use}

R2DBC를 쓰는 코루틴 중심 서비스에 선택합니다. 주변 API가 블로킹이거나 가상 스레드 기반이면 JDBC가 자연스럽습니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-r2dbc")
}
```

## 핵심 개념 {#concepts}

suspend transaction의 조건부 쓰기, 만료 시각, owner token으로 lease를 구현합니다. 취소된 호출은 자신이 가진 lease만 정리해야 합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val elector = ExposedR2DbcSuspendLeaderElector(database)
elector.runIfLeader("projection-refresh") { refreshProjection() }
```

## 작업별 API {#api-by-task}

suspend 단일/group elector, `R2dbcDatabase` 확장 함수, factory, initializer, suspend history sink를 사용합니다.

## 권장 패턴 {#patterns}

acquire/use/release를 structured scope에 두고 블로킹 JDBC를 숨겨 넣지 않습니다. 공통 스키마 migration을 먼저 적용하세요.

## 연동 {#integrations}

DB, schema, 릴리스가 같다면 JDBC와 namespace를 공유할 수 있습니다. Spring과 Ktor는 suspend factory/elector를 사용합니다.

## 설정 {#configuration}

wait, lease, retry, schema, R2DBC pool, statement timeout, 생명주기 소유권을 조정합니다.

## 실패 유형과 해결 방법 {#failures}

경쟁은 `null`입니다. 취소는 owner-safe 정리 후 다시 던지며 연결·transaction 실패는 예외로 드러납니다.

## 운영 {#operations}

acquire 지연, pool 대기, retry, 취소 정리, DB 부하를 관측합니다.

## 테스트 {#testing}

실제 R2DBC DB에서 두 client 경쟁, 그룹 슬롯, 만료, acquire/action 중 취소를 검증합니다.

## 학습 경로와 예제 {#workshops}

JDBC와 비교한 뒤 migration-gate와 Ktor 예제에서 생명주기 통합을 확인하세요.

## 제약 사항 {#limitations}

R2DBC는 막힌 스레드를 줄이지만 DB 왕복과 가용성 위험을 없애지는 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 현재 개발 브랜치가 아니라 `0.4.0` 배포 태그의 README 자산을 바이트 단위로 그대로 옮긴 것입니다. 따라서 이후 SNAPSHOT 변경이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 SVG 원본이 열립니다.

### leader exposed r2dbc 클래스 구조도

[![leader exposed r2dbc 클래스 구조도](../../assets/readme-diagrams/leader-exposed-r2dbc-class-01.png)](../../assets/readme-diagrams/leader-exposed-r2dbc-class-01.svg)

_배포본 README: [`leader-exposed-r2dbc/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-exposed-r2dbc/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[Elector](../../../../leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/ExposedR2DbcSuspendLeaderElector.kt) · [Initializer](../../../../leader-exposed-r2dbc/src/main/kotlin/io/bluetape4k/leader/exposed/r2dbc/lock/ExposedR2dbcSchemaInitializer.kt) · [안정판 안내](../../../../leader-exposed-r2dbc/README.ko.md)


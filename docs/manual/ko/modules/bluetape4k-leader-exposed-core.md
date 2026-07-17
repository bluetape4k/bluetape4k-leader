---
manualId: "bluetape4k-leader-exposed-core"
id: "bluetape4k-leader-exposed-core"
title: "Exposed 백엔드 Core"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-exposed-core"
sourceDir: "leader-exposed-core"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-exposed-core
---

# Exposed 백엔드 Core

> 라이브러리 모듈

## 제공하는 기능 {#problem}

JDBC와 R2DBC elector가 함께 쓰는 SQL 스키마, 테이블 매핑, 재시도 정책, history codec, migration을 정의합니다. 일반 애플리케이션은 adapter를 선택합니다.

## 사용하기 좋은 경우 {#when-to-use}

스키마 migration을 직접 소유하거나 별도 SQL adapter를 만들 때 직접 사용합니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-exposed-core`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-core")
}
```

## 핵심 개념 {#concepts}

lock, group slot, history 테이블이 key와 만료 규칙을 공유합니다. 조건부 SQL transaction이 소유권을 정하고 재시도는 일시적 충돌을 처리합니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-exposed-jdbc")
}
```

## 작업별 API {#api-by-task}

주요 지원 API는 `ExposedLeaderSchema`, 테이블 객체, `RetryStrategy`입니다. 애플리케이션은 보통 adapter initializer에서 시작합니다.

## 권장 패턴 {#patterns}

스키마 생성 주체를 하나로 정하고 전용 namespace와 unique key를 유지합니다. 애플리케이션보다 migration을 먼저 배포하세요.

## 연동 {#integrations}

JDBC와 R2DBC는 테이블과 메타데이터 형식을 공유합니다. 설정이 같으면 같은 논리 namespace를 관측할 수 있습니다.

## 설정 {#configuration}

스키마 이름과 retry는 adapter가 정합니다. DB 시계, isolation, timeout, pool 크기는 애플리케이션 책임입니다.

## 실패 유형과 해결 방법 {#failures}

테이블 누락과 권한 오류는 배포 실패이지 경쟁이 아닙니다. Serialization 충돌은 재시도할 수 있지만 잘못된 migration은 드러내야 합니다.

## 운영 {#operations}

테이블 증가, history 보존, dead tuple, DB 지연을 관측합니다. 애플리케이션 로컬 시계만 보고 행을 지우지 마세요.

## 테스트 {#testing}

dialect별 스키마와 JDBC/R2DBC의 key, 만료 단위, 메타데이터 일치를 검증합니다.

## 학습 경로와 예제 {#workshops}

migration 소유권이 분리돼 있다면 JDBC/R2DBC보다 먼저 읽으세요. migration-gate 예제에 배포 작업 선출이 나와 있습니다.

## 제약 사항 {#limitations}

이 모듈만으로 elector가 완성되지 않으며 driver, pool, transaction manager도 고르지 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 `0.4.0` 배포본의 README 자산을 해당 배포 커밋에서 직접 불러옵니다. 이후 SNAPSHOT이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 같은 배포 커밋의 SVG 원본이 열립니다.

### leader exposed core ERD 다이어그램

[![leader exposed core ERD 다이어그램](https://raw.githubusercontent.com/bluetape4k/bluetape4k-leader/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-exposed-core-erd-01.png)](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/docs/images/readme-diagrams/leader-exposed-core-erd-01.svg)

_배포본 README: [`leader-exposed-core/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-exposed-core/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[스키마](../../../../leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/ExposedLeaderSchema.kt) · [Lock 테이블](../../../../leader-exposed-core/src/main/kotlin/io/bluetape4k/leader/exposed/tables/LeaderLockTable.kt) · [안정판 안내](../../../../leader-exposed-core/README.ko.md)


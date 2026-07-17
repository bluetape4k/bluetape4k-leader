---
manualId: "bluetape4k-leader-micrometer"
id: "bluetape4k-leader-micrometer"
title: "Micrometer 계측"
locale: "ko"
kind: "library"
gradlePath: ":bluetape4k-leader-micrometer"
sourceDir: "leader-micrometer"
releaseRef: "0.4.0"
artifact: io.github.bluetape4k.leader:bluetape4k-leader-micrometer
---

# Micrometer 계측

> 라이브러리 모듈

## 제공하는 기능 {#problem}

단일 선출기, 그룹 선출기, 코루틴 선출기, 코어 수명 주기 리스너, Spring AOP 기록기, 이력 저장소에 Micrometer 지표를 추가합니다. 선출의 정합성에는 영향을 주지 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

백엔드별 락 획득, 건너뛴 실행, 실패, 실행 시간, 이력 지표가 필요할 때 사용합니다. 지표를 수집할 `MeterRegistry`가 없다면 굳이 추가하지 않아도 됩니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-micrometer`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer")
}
```

## 핵심 개념 {#concepts}

계측 래퍼는 소유권 판단을 내부 선출기에 위임합니다. 메트릭은 시도와 결과를 설명할 뿐 실행 권한을 주지 않습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val instrumented = InstrumentedLeaderElector(
    delegate = elector,
    registry = meterRegistry
)
instrumented.runIfLeader("daily-report") { generateReport() }
```

## 작업별 API {#api-by-task}

선출기를 직접 감싸거나 `MicrometerLeaderElectionListener`를 등록합니다. Spring AOP에는 전용 기록기를, 이력 저장소에는 오류를 격리하는 기록기 데코레이터를 사용합니다.

## 권장 패턴 {#patterns}

논리 선출기 하나당 래퍼 하나만 등록하고 태그 값의 종류를 제한하세요. 테넌트나 요청에서 만든 락 이름은 태그로 그대로 쓰지 않습니다.

## 연동 {#integrations}

모든 코어 호환 백엔드와 함께 쓸 수 있습니다. `prometheus-dashboard` 예제에서 지표를 내보내고 대시보드와 경보에 연결하는 방법을 확인할 수 있습니다.

## 설정 {#configuration}

레지스트리, 공통 태그, 미터 필터, 히스토그램과 백분위수 정책, 카디널리티 제한은 애플리케이션에서 정합니다. 모니터링 백엔드까지 설정하지는 않습니다.

## 실패 유형과 해결 방법 {#failures}

메트릭 레지스트리의 실패가 소유권 판단으로 번지면 안 됩니다. 래퍼를 중복 등록하면 이중으로 집계되고, 제한 없이 늘어나는 락 이름 태그는 메모리를 소모합니다.

## 운영 {#operations}

정상적인 건너뛰기와 백엔드 실패·리스 연장 실패를 구분해 경보를 설정하세요. 실행 시간과 시도량은 백엔드 지연과 함께 봅니다.

## 테스트 {#testing}

단순한 레지스트리로 미터 이름, 태그, 결과, 실패, 코루틴 취소, 아무 작업도 하지 않는 경우, 이력 데코레이터의 중복 집계 여부를 검증합니다.

## 학습 경로와 예제 {#workshops}

코어 결과 계약을 익힌 뒤 `prometheus-dashboard`를 실행하세요. 어떤 실패에 대응할지는 선택한 백엔드 페이지와 함께 판단합니다.

## 제약 사항 {#limitations}

메트릭은 유실될 수 있는 관측값입니다. 영속적인 감사 기록, 추적, 경보, 대시보드, 낮은 카디널리티를 유지하는 정책까지 자동으로 제공하지는 않습니다.

<!-- release-readme-diagrams:start -->
## 배포본 다이어그램 {#release-diagrams}

아래 그림은 현재 개발 브랜치가 아니라 `0.4.0` 배포 태그의 README 자산을 바이트 단위로 그대로 옮긴 것입니다. 따라서 이후 SNAPSHOT 변경이 아니라 이 매뉴얼 버전의 구조와 실행 흐름을 보여 줍니다. 미리보기를 누르면 SVG 원본이 열립니다.

### leader-micrometer instrumentation 아키텍처

[![leader-micrometer instrumentation 아키텍처](../../assets/readme-diagrams/leader-micrometer-architecture-01.png)](../../assets/readme-diagrams/leader-micrometer-architecture-01.svg)

_배포본 README: [`leader-micrometer/README.ko.md`](https://github.com/bluetape4k/bluetape4k-leader/blob/17ab7f872c1f96318c73d3580729cac20a67e017/leader-micrometer/README.ko.md)_

<!-- release-readme-diagrams:end -->

## 근거 자료 {#sources}

[계측 elector](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/InstrumentedLeaderElectors.kt) · [Listener](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderElectionListener.kt) · [안정판 안내](../../../../leader-micrometer/README.ko.md)

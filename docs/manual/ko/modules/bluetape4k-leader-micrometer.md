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

단일/group/suspend elector, Core 생명주기 listener, Spring AOP recorder, history sink에 Micrometer 지표를 덧붙입니다. 선출의 정합성은 바꾸지 않습니다.

## 사용하기 좋은 경우 {#when-to-use}

백엔드별 acquire, skip, 실패, 실행 시간, history 지표가 필요할 때 사용합니다. 지표를 소비할 registry가 없다면 굳이 추가하지 않아도 됩니다.

## 의존성 좌표 {#coordinates}

Maven 좌표: `io.github.bluetape4k.leader:bluetape4k-leader-micrometer`

```kotlin
dependencies {
    implementation(platform("io.github.bluetape4k:bluetape4k-dependencies:<version>"))
    implementation("io.github.bluetape4k.leader:bluetape4k-leader-micrometer")
}
```

## 핵심 개념 {#concepts}

계측 wrapper는 소유권 판단을 아래 elector에 위임합니다. Metric은 시도와 결과를 설명할 뿐 실행 권한을 주지 않습니다.

## 빠르게 시작하기 {#quick-start}

```kotlin
val instrumented = InstrumentedLeaderElector(
    delegate = elector,
    registry = meterRegistry
)
instrumented.runIfLeader("daily-report") { generateReport() }
```

## 작업별 API {#api-by-task}

직접 elector를 감싸거나 `MicrometerLeaderElectionListener`를 붙입니다. Spring에는 AOP recorder, history에는 safe recorder decorator를 사용합니다.

## 권장 패턴 {#patterns}

논리 elector 하나당 wrapper 하나만 등록하고 tag 수를 제한하세요. Tenant나 request에서 만든 lock name은 tag로 그대로 쓰지 않습니다.

## 연동 {#integrations}

모든 Core 호환 백엔드와 함께 쓸 수 있습니다. prometheus-dashboard 예제에 export와 dashboard/alert 연결이 나와 있습니다.

## 설정 {#configuration}

registry, common tag, meter filter, histogram/percentile 정책, cardinality 제한은 애플리케이션에서 정합니다. 모니터링 백엔드까지 설정하지는 않습니다.

## 실패 유형과 해결 방법 {#failures}

metric registry 실패가 소유권 판단으로 번지면 안 됩니다. Wrapper를 중복 등록하면 이중 집계되고 무제한 lock-name tag는 메모리를 소모합니다.

## 운영 {#operations}

정상 skip과 백엔드 실패·연장 실패를 나눠 alert하세요. 실행 시간과 시도량을 백엔드 지연과 함께 봅니다.

## 테스트 {#testing}

단순 registry로 meter 이름, tag, 결과, 실패, suspend 취소, no-op, history decorator의 중복 집계 여부를 검증합니다.

## 학습 경로와 예제 {#workshops}

Core 결과 계약을 익힌 뒤 prometheus-dashboard를 실행하세요. 어떤 실패에 대응할지는 선택한 백엔드 페이지와 함께 판단합니다.

## 제약 사항 {#limitations}

Metric은 유실될 수 있는 관측값입니다. Durable audit, tracing, alert, dashboard, 저카디널리티 정책을 자동으로 제공하지는 않습니다.

## 근거 자료 {#sources}

[계측 elector](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/InstrumentedLeaderElectors.kt) · [Listener](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderElectionListener.kt) · [안정판 안내](../../../../leader-micrometer/README.ko.md)


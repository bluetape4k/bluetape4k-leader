---
title: "Micrometer 연동"
description: "카디널리티가 높은 lock name을 통제하면서 선출 결정과 AOP 실행을 측정합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Micrometer 연동

카디널리티가 높은 lock name을 통제하면서 선출 결정과 AOP 실행을 측정합니다.

## 두 가지 연동 지점

`MicrometerLeaderElectionListener`는 elector 이벤트에서 수명 주기 결정을 기록합니다. `MicrometerLeaderAopMetricsRecorder`는 annotation 기반 실행을 계측합니다. 실제 호출 경계에 맞는 하나를 선택하고, dashboard 모델을 명확히 정하지 않은 채 같은 호출을 양쪽에서 중복 집계하지 않습니다.

## Tag

backend, result, 안정된 job family처럼 개수가 제한된 tag를 사용합니다. tenant 정보가 들어간 lock name은 label로 만들기 전에 정규화하거나 거부합니다. 진단에 정확한 lock name이 필요하면 구조화 로그에 남깁니다.

## Dashboard

건수는 duration과 백엔드 상태와 함께 봅니다. elected count만으로는 작업 정체를 알 수 없고, skipped만으로는 정상 경쟁과 과도한 중복 시도를 구분할 수 없습니다. Prometheus 예제의 scrape와 dashboard 구성을 출발점으로 삼되 이름과 경보 임계값은 운영 환경에 맞게 바꿉니다.

## 릴리스 소스

- [`leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderElectionListener.kt`](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderElectionListener.kt)
- [`leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorder.kt`](../../../../leader-micrometer/src/main/kotlin/io/bluetape4k/leader/micrometer/MicrometerLeaderAopMetricsRecorder.kt)
- [`examples/prometheus-dashboard/README.ko.md`](../../../../examples/prometheus-dashboard/README.ko.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [관측과 운영](../guides/observability-and-operations.md)
- [Spring Boot 연동](spring-boot.md)

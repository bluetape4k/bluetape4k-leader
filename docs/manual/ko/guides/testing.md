---
title: "리더 선출 테스트"
description: "결정적인 core 계약, 실제 백엔드 소유권, 다중 인스턴스 시나리오의 세 단계로 시험합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 리더 선출 테스트

결정적으로 재현할 수 있는 코어 계약, 실제 백엔드 소유권, 다중 인스턴스 시나리오의 세 단계로 시험합니다.

## 단위 계약

로컬 선출기나 테스트 대역을 사용해 선출됐을 때만 작업이 실행되는지 확인합니다. 건너뛴 실행은 예외가 아니어야 하고, 선출된 작업이 반환한 `null`은 결과 API로 구분되어야 하며, 작업 예외는 호출자에게 전달되어야 합니다. 락 이름은 고정하고 제한 시간은 짧고 명확하게 둡니다.

## 백엔드 통합

실제 클라이언트를 Testcontainers나 에뮬레이터에 연결합니다. 원자적 경쟁, 소유자를 확인하는 조건부 해제, 만료 후 재획득, 최소 리스, 상태 스냅숏, 그룹 슬롯 상한, 지원되는 연장 경로를 검증합니다. 무거운 백엔드 시험은 공유 자원의 간섭을 피하도록 순차 실행합니다.

## 시나리오 시험

애플리케이션 인스턴스 두 개나 독립적으로 구성한 선출기 두 개를 실행합니다. 외부 효과가 한 번만 생기는지 확인한 뒤 리더를 종료하고, 문서에 적힌 리스나 세션 경계가 지나면 다른 인스턴스가 인계하는지 확인합니다. 취소와 정상 종료도 시험합니다. 성공 경로만으로 네트워크 분할 안전성이 증명되지는 않으므로 테스트 환경이 재현하지 못하는 범위를 기록합니다.

## 릴리스 소스

- [`leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractLeaderElectorLeaderIdContractTest.kt`](../../../../leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractLeaderElectorLeaderIdContractTest.kt)
- [`leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderElectorTest.kt`](../../../../leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderElectorTest.kt)
- [`examples/batch-scheduler/src/test/kotlin/io/bluetape4k/leader/examples/batch/BatchSchedulerTest.kt`](../../../../examples/batch-scheduler/src/test/kotlin/io/bluetape4k/leader/examples/batch/BatchSchedulerTest.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [실패와 취소](failure-and-cancellation.md)
- [관측과 운영](observability-and-operations.md)

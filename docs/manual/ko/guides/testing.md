---
title: "리더 선출 테스트"
description: "결정적인 core 계약, 실제 백엔드 소유권, 다중 인스턴스 시나리오의 세 단계로 시험합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 리더 선출 테스트

결정적인 core 계약, 실제 백엔드 소유권, 다중 인스턴스 시나리오의 세 단계로 시험합니다.

## 단위 계약

로컬 elector나 fake를 사용해 선출됐을 때만 작업이 실행되고, skipped가 예외가 아니며, 선출된 작업의 `null`은 결과 API로 구분되고, 작업 예외가 전달되는지 확인합니다. lock name은 고정하고 timeout은 짧고 명확하게 둡니다.

## 백엔드 통합

실제 client를 Testcontainers나 emulator에 연결합니다. 원자적 경쟁, 소유자 조건부 해제, 만료 후 재획득, 최소 리스, 상태 snapshot, group slot 상한, 지원되는 연장 경로를 검증합니다. 무거운 백엔드 시험은 공유 자원 노이즈를 피하도록 순차 실행합니다.

## 시나리오 시험

애플리케이션 인스턴스 두 개나 독립적으로 구성한 elector 두 개를 띄웁니다. 외부 효과가 한 번만 생기는지 본 뒤 leader를 종료하고 문서화된 lease/session 경계가 지나면 takeover되는지 확인합니다. 취소와 정상 종료도 시험합니다. 성공 경로만으로 partition 안전성이 증명되지는 않으므로 fixture가 재현하지 못하는 범위를 기록합니다.

## 릴리스 소스

- [`leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractLeaderElectorLeaderIdContractTest.kt`](../../../../leader-core/src/testFixtures/kotlin/io/bluetape4k/leader/contract/AbstractLeaderElectorLeaderIdContractTest.kt)
- [`leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderElectorTest.kt`](../../../../leader-redis-lettuce/src/test/kotlin/io/bluetape4k/leader/lettuce/LettuceSuspendLeaderElectorTest.kt)
- [`examples/batch-scheduler/src/test/kotlin/io/bluetape4k/leader/examples/batch/BatchSchedulerTest.kt`](../../../../examples/batch-scheduler/src/test/kotlin/io/bluetape4k/leader/examples/batch/BatchSchedulerTest.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [실패와 취소](failure-and-cancellation.md)
- [관측과 운영](observability-and-operations.md)

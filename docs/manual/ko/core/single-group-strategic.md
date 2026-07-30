---
title: "단일·그룹·전략 선출 계약"
description: "각 모델이 보장하는 범위와 애플리케이션이 별도로 맡아야 할 작업 분배를 구분합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 단일·그룹·전략 선출 계약

각 모델이 보장하는 범위와 애플리케이션이 별도로 맡아야 할 작업 분배를 구분합니다.

## 대화형 시각화 자료

먼저 `LeaderElector`의 락·리스 상세 흐름을 익힌 다음, `LeaderGroupElector` 자료에서 `1 → N` 슬롯 수용량의 차이에 집중합니다. 두 시뮬레이션 모두 릴리스에 고정된 Redis/Lettuce 모델을 사용합니다.

[![LeaderElector 락과 리스 시각화 자료](../../assets/visual-companions/leader-elector.ko.png)](/ko/visual-companions/bluetape4k-leader/leader-elector/)

[![LeaderGroupElector 슬롯 수용량 시각화 자료](../../assets/visual-companions/leader-group-elector.ko.png)](/ko/visual-companions/bluetape4k-leader/leader-group-elector/)

## 단일

같은 lock name에는 규칙을 따르는 owner가 최대 하나만 존재합니다. 리스가 만료되거나 호출자가 재시도하면 업무 관점에서는 at-least-once가 될 수 있으므로 외부 효과는 멱등해야 합니다.

## 그룹

그룹은 최대 `maxLeaders`개의 독립 slot을 제공하고 `activeCount`, `availableSlots`, `isFull`을 보여 줍니다. slot은 동시 실행 수만 제한할 뿐 서로 다른 작업을 배정하지 않습니다. revoke 이벤트에서 slot을 식별하지 못하는 백엔드가 있어 group event projection의 구체적인 leader 목록은 비어 있을 수 있습니다.

## 전략

전략 선출은 후보 등록과 winner 선택을 분리합니다. FIFO, seed 기반 random, scored 전략과 idle-time, success-rate, recent-success, weighted scorer를 제공합니다. 실행 위치를 최적화하는 기능이며, 임의의 공유 쓰기에 대한 분산 lock을 자동으로 제공하지는 않습니다.

## 릴리스 소스

- [`leader-core/README.ko.md`](../../../../leader-core/README.ko.md)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupState.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupState.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/StrategicLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/StrategicLeaderElector.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [선출 모델 선택](../guides/election-model-selection.md)
- [실행 API](execution-apis.md)

---
title: "선출 모델 선택"
description: "업무가 허용하는 동시 실행 수와 배치 규칙에 따라 단일, 그룹, 전략 선출을 고릅니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 선출 모델 선택

업무가 허용하는 동시 실행 수와 배치 규칙에 따라 단일, 그룹, 전략 선출을 고릅니다.

![선출 모델과 실행 API 선택 지도](../../assets/architecture/model-decision-map.png)

## 단일 리더

이름이 같은 작업을 한 인스턴스만 실행해야 한다면 `LeaderElector` 또는 `SuspendLeaderElector`를 사용합니다. 정산 스케줄, 마이그레이션 gate, control-plane reconcile이 대표적인 사례입니다. lock name 하나가 동시 실행 경계를 이룹니다.

## 리더 그룹

최대 `maxLeaders`개 작업자가 동시에 실행해도 된다면 `LeaderGroupElector`를 사용합니다. 각 당선자는 slot 하나를 소유합니다. 이는 분산 semaphore이지 작업 분배기가 아닙니다. 선출된 두 작업자가 같은 항목을 처리하지 않도록 queue나 partition 규칙은 애플리케이션이 따로 마련해야 합니다.

## 전략 선출

먼저 lock을 잡은 노드가 아니라 후보 정보에 따라 적합한 노드를 골라야 할 때 전략 선출을 사용합니다. FIFO, seed가 있는 random, score, weighted score 전략을 제공합니다. 후보 등록 정보가 낡으면 부적합한 노드를 고를 수 있으므로 freshness 관리가 필요합니다.

## 실전 선택 규칙

처음에는 단일 리더로 시작합니다. 측정 결과 처리량 때문에 제한된 병렬 실행이 필요할 때만 그룹으로 확장합니다. 어느 노드가 실행하느냐가 요구사항이고 후보 상태를 꾸준히 관리할 수 있을 때만 전략 선출을 선택합니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderGroupElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/StrategicLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/StrategicLeaderElector.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [단일·그룹·전략 선출 계약](../core/single-group-strategic.md)
- [실행 API 선택](execution-model-selection.md)

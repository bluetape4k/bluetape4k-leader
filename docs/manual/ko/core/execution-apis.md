---
title: "실행 API"
description: "각 interface의 lambda, 반환형, scheduler, 취소 경계를 연결해 이해합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 실행 API

각 interface의 lambda, 반환형, scheduler, 취소 경계를 연결해 이해합니다.

## Interface 지도

`LeaderElector`는 `() -> T`를 받아 `T?`를 반환합니다. `AsyncLeaderElector`는 `() -> CompletableFuture<T>`를 받아 `CompletableFuture<T?>`를 돌려줍니다. `VirtualThreadLeaderElector`는 `() -> T`와 `VirtualFuture<T?>`를 사용합니다. `SuspendLeaderElector`는 suspend lambda를 받아 `T?`를 반환합니다. 그룹 interface도 블로킹과 suspend 형태를 제공합니다.

## 편의상 변환하지 않는다

애플리케이션 경계에 맞는 interface를 고릅니다. 블로킹 백엔드를 coroutine으로 감싼다고 I/O가 non-blocking이 되지 않습니다. 임의로 thread를 바꾸면 Redisson 연장처럼 thread-bound 소유권이 깨질 수도 있습니다. 백엔드가 제공하는 native interface를 우선합니다.

## Slot overload

`LeaderSlot`은 lock name과 audit leader id를 함께 전달합니다. 기본 bridge overload는 경고를 남기며 백엔드가 해당 식별자를 실제로 기록했는지 증명할 수 없습니다. 백엔드 구현은 slot과 result overload를 함께 재정의해야 합니다. bridge 경로의 `Elected.leaderId`가 null이 아니라고 가정하지 않습니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [실행 결과의 의미](result-semantics.md)
- [실행 API 선택](../guides/execution-model-selection.md)

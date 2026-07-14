---
title: "실행 API 선택"
description: "애플리케이션의 동시성 모델과 취소 규칙에 맞는 elector API를 선택합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 실행 API 선택

애플리케이션의 동시성 모델과 취소 규칙에 맞는 elector API를 선택합니다.

## 블로킹과 CompletableFuture

`LeaderElector`는 호출 경로에서 동기 람다를 실행합니다. `AsyncLeaderElector`는 `CompletableFuture` 작업을 받고 기본적으로 가상 스레드 executor를 사용합니다. 명령형 배치처럼 실행 수가 제한된 작업에는 블로킹 API를, 상위 계약이 이미 Future라면 async API를 선택합니다.

## 가상 스레드

`VirtualThreadLeaderElector`는 값을 직접 반환하는 람다를 받고 `VirtualFuture`를 돌려줍니다. Java 21 이상에서 블로킹 선출 시도를 많이 띄울 때 유용합니다. 다만 블로킹 백엔드 I/O 자체가 non-blocking으로 바뀌는 것은 아니므로 연결 풀은 별도로 산정해야 합니다.

## 코루틴

코루틴 기반 서비스에는 `SuspendLeaderElector`가 자연스럽습니다. 처음부터 끝까지 non-blocking 경로가 필요하면 Exposed R2DBC 같은 suspend 구현을 선택합니다. suspend 전용 delegate를 `runBlocking`으로 우회하지 말고, 취소가 호출 범위의 리스를 해제한 뒤 그대로 전달되는지 확인합니다.

## 결과와 취소

모든 실행 모델에 명시적 결과 API가 있습니다. 취소는 `ActionFailed`로 바뀌지 않습니다. 블로킹과 suspend 경로에서는 다시 던지고, Future 경로에서는 예외 완료됩니다. `await()`나 `join()`처럼 실제 호출자가 결과를 관찰하는 지점에서 시험해야 합니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [실행 API](../core/execution-apis.md)
- [실패와 취소](failure-and-cancellation.md)

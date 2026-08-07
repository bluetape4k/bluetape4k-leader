---
title: "실행 API 선택"
description: "애플리케이션의 동시성 모델과 취소 규칙에 맞는 선출기 API를 선택합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 실행 API 선택

애플리케이션의 동시성 모델과 취소 규칙에 맞는 선출기 API를 선택합니다.

## 블로킹과 CompletableFuture

`LeaderElector`는 호출한 스레드에서 동기 람다를 실행합니다. `AsyncLeaderElector`는 `CompletableFuture` 작업을 받고 기본적으로 가상 스레드 실행기를 사용합니다. 명령형 배치처럼 동시에 실행할 작업 수가 제한된 경우에는 블로킹 API를, 상위 호출 계약이 이미 `CompletableFuture`라면 비동기 API를 선택합니다.

## 가상 스레드

`VirtualThreadLeaderElector`는 값을 직접 반환하는 람다를 받고 `VirtualFuture`를 반환합니다. Java 21 이상에서 블로킹 선출 시도를 많이 실행할 때 유용합니다. 다만 블로킹 백엔드 I/O 자체가 비차단 방식으로 바뀌는 것은 아니므로 연결 풀 용량은 별도로 산정해야 합니다.

## 코루틴

코루틴 기반 서비스에는 `SuspendLeaderElector`가 자연스럽습니다. 처음부터 끝까지 비차단 경로가 필요하면 Exposed R2DBC 같은 코루틴 구현을 선택합니다. 코루틴 전용 위임 객체를 `runBlocking`으로 우회하지 말고, 취소가 호출 범위의 리스를 해제한 뒤 호출자에게 그대로 전달되는지 확인합니다.

## 결과와 취소

모든 실행 모델에 명시적인 결과 API가 있습니다. 취소는 `ActionFailed`로 바뀌지 않습니다. 블로킹과 코루틴 경로에서는 다시 던지고, `CompletableFuture` 경로에서는 예외로 완료됩니다. `await()`나 `join()`처럼 실제 호출자가 결과를 확인하는 지점에서 시험해야 합니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/AsyncLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/VirtualThreadLeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/coroutines/SuspendLeaderElector.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [실행 API](../core/execution-apis.md)
- [실패와 취소](failure-and-cancellation.md)

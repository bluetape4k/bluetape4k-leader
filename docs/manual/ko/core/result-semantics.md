---
title: "실행 결과의 의미"
description: "작업이 null을 반환할 수 있어도 실행, 건너뜀, 실패를 정확히 구분합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 실행 결과의 의미

작업이 null을 반환할 수 있어도 실행, 건너뜀, 실패를 정확히 구분합니다.

## Nullable API

`runIfLeader(): T?`는 간결합니다. 작업 결과가 오면 선출됐고 `null`이면 건너뛴 것입니다. 다만 `T` 자체가 nullable이면 두 경우를 구분할 수 없습니다.

## 명시적 결과

`LeaderRunResult.Elected(value, leaderId?)`는 작업이 실행됐음을 보장하며 `value`는 null일 수 있습니다. `Skipped`는 획득에 실패해 작업을 실행하지 않았다는 뜻입니다. `ActionFailed(cause)`는 소유권을 얻어 작업을 시작했지만 실패한 경우입니다. 작업 시작 전의 선출·백엔드 오류는 action failure로 잘못 포장하지 않고 던집니다.

## 제어 흐름 예외

`CancellationException`은 감싸지 않고 전파합니다. 블로킹 경로는 `InterruptedException`을 다시 던지기 전에 interrupt flag를 복원합니다. Future와 가상 스레드 호출자는 예외 완료를 예상하고 실제 결과를 소비하는 지점에서 원인을 확인해야 합니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt)
- [`leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt`](../../../../leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [실행 API](execution-apis.md)
- [실패와 취소](../guides/failure-and-cancellation.md)

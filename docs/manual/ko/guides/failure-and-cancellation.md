---
title: "실패와 취소"
description: "일반 경쟁, 작업 실패, 백엔드 실패, 취소, 소유권 상실을 서로 다른 사건으로 다룹니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# 실패와 취소

일반 경쟁, 작업 실패, 백엔드 실패, 취소, 소유권 상실을 서로 다른 사건으로 다룹니다.

## 다섯 가지 결과

경쟁에서 밀리면 skipped입니다. 작업 예외는 nullable API에서 그대로 전달되고 결과 API에서는 `ActionFailed`가 됩니다. acquire 과정의 백엔드 오류는 인프라 장애이며 contention으로 숨기지 않습니다. 취소도 그대로 전파됩니다. 리스 연장에서는 `NotHeld`, `WrongThread`, `BackendError`가 추가로 발생할 수 있습니다.

## 호출자의 대응

skipped와 failed를 별도 지표로 집계합니다. 백엔드 오류는 분류한 뒤, 작업이 이미 시작됐을 가능성을 확인하고 재시도합니다. 작업 예외를 무조건 재시도하면 안 됩니다. `NotHeld`가 나오면 다른 contender가 소유권을 얻을 수 있으므로 남은 외부 쓰기를 멈추거나 fencing 검사를 거칩니다.

## 종료 순서

새로운 선출 시도부터 막고, 호출 범위의 coroutine job을 취소한 다음, 제한 시간 안에 진행 중인 작업을 기다립니다. 그 뒤 elector가 사용하는 백엔드 client를 소유권 규칙에 맞춰 닫습니다. 프레임워크 연동이 애플리케이션 수명 주기에 이 순서를 묶어 주더라도 작업 자체의 취소 안전성은 애플리케이션 책임입니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderRunResult.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/ExtendOutcome.kt)
- [`leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt`](../../../../leader-core/src/test/kotlin/io/bluetape4k/leader/LeaderRunResultTest.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [리스 수명 주기](lease-lifecycle.md)
- [관측과 운영](observability-and-operations.md)

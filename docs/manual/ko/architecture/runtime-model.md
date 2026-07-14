---
title: "실행 구조"
description: "한 번의 선출이 경쟁 진입부터 소유권 획득, 작업 실행, 관측, 해제까지 어떻게 흐르는지 설명합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 실행 구조

한 번의 선출이 경쟁 진입부터 소유권 획득, 작업 실행, 관측, 해제까지 어떻게 흐르는지 설명합니다.

![리더 선출의 리스 수명 주기](../../assets/architecture/election-lifecycle.png)

## 핵심 경로

선출기는 락 이름을 검증하고 `waitTime` 동안 기다리며 백엔드에 원자적으로 소유권을 획득해 달라고 요청합니다. 획득하지 못하면 건너뛴 이벤트를 내고 `null` 또는 `LeaderRunResult.Skipped`를 반환합니다. 획득하면 호출 범위에 묶인 락 핸들을 만들고 선출 이벤트를 낸 뒤 작업을 실행합니다. 완료나 실패를 기록하고 나면 자신의 소유권과 일치할 때만 리스를 해제합니다.

## 리스는 트랜잭션이 아니다

유효한 리스는 같은 규칙을 따르는 다른 후보가 보호 구간에 들어오는 일을 막습니다. 하지만 외부 쓰기를 되돌려 주지는 않으며, 프로세스가 멈추거나 네트워크가 분리된 뒤 이미 시작한 작업을 강제로 중단할 수도 없습니다. 따라서 작업은 멱등하게 만들고, 데이터를 변경하는 후속 시스템에서는 가능하면 펜싱 토큰을 확인해야 합니다.

## 관측 값은 참고 자료다

`state(lockName)`과 이벤트 스트림은 운영 상태를 이해하는 데 유용하지만 어디까지나 특정 시점의 스냅숏입니다. 상태를 읽은 뒤 획득 가능 여부를 판단하면 안 됩니다. 그 결정은 백엔드의 원자적 획득 경로에서만 내릴 수 있습니다. `leaseUntil`도 백엔드에 따라 없거나 근삿값일 수 있습니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElector.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderState.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderState.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLease.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLease.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [리스 수명 주기](../guides/lease-lifecycle.md)
- [실행 결과의 의미](../core/result-semantics.md)

---
title: "리스 수명 주기"
description: "대기 시간, 최대 리스, 최소 보유 시간, 연장 여부를 기본값이 아니라 측정 결과로 정합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 리스 수명 주기

대기 시간, 최대 리스, 최소 보유 시간, 연장 여부를 기본값이 아니라 측정 결과로 정합니다.

## 네 가지 시간 결정

`waitTime`은 획득을 기다릴 상한, `leaseTime`은 소유권의 상한입니다. `minLeaseTime`은 작업이 빨리 끝나도 너무 일찍 다시 획득하지 못하게 하며 리스보다 길 수 없습니다. `autoExtend`는 지원되는 단일 리더 리스를 작업 중에 주기적으로 갱신합니다. 기본값은 각각 5초, 60초, 0초, false입니다.

## 값을 정하는 방법

작업 시간 p99와 백엔드 지연을 측정합니다. 고정 리스는 p99에 GC, 스케줄링, 네트워크 변동을 더한 시간보다 길어야 합니다. 선출되지 않으면 건너뛸 스케줄 작업은 대기를 짧게 두고, 실제로 줄을 세울 때만 늘립니다. 최소 보유 시간은 후속 시스템 호출 간격이 필요할 때 쓰되 rate limiter 대신 사용하지 않습니다.

## 만료와 해제

정상 완료 시에는 owner token이 일치할 때 소유권을 해제합니다. 프로세스가 죽으면 TTL 또는 session 만료가 해제를 맡습니다. 작업이 리스보다 길어질 수 있다면 지원되는 자동 연장을 쓰거나 bounded checkpoint로 나눕니다. 연장을 사용해도 partition과 pause 경쟁을 완전히 없앨 수는 없으므로 외부 쓰기는 멱등하게 설계합니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderElectionOptions.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLeaseAutoExtender.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LockExtender.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [리스 연장](../core/lease-extension.md)
- [실패와 취소](failure-and-cancellation.md)

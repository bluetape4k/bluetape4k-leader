---
title: "Redis 백엔드"
description: "명령 수준 제어가 필요하면 Lettuce를, Redisson 생태계를 사용 중이면 Redisson을 선택하되 thread-bound 소유권을 주의합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Redis 백엔드

명령 수준 제어가 필요하면 Lettuce를, Redisson 생태계를 사용 중이면 Redisson을 선택하되 thread-bound 소유권을 주의합니다.

## 공통 계약

두 모듈 모두 단일, 그룹, suspend, factory, state, 전략 선출 구현을 제공합니다. owner token을 확인해 조건부로 해제하고 공통 lease auto-extender를 사용합니다. Redis 가용성과 key 만료 정책도 정확성 경계에 포함됩니다.

## Lettuce

Lettuce는 sync 명령과 coroutine 친화 경로를 제공하므로 애플리케이션이 이미 Lettuce connection을 소유할 때 잘 맞습니다. connection 수명과 command timeout은 선출의 `waitTime`과 별도로 검증합니다.

## Redisson

Redisson은 `RLock`과 semaphore 계열 기능을 활용합니다. 일부 소유권은 획득한 thread에 묶이므로 다른 thread에서 연장하면 `WrongThread`가 됩니다. 0.5.0 elector는 명시적 lease를 넘기므로 벤치마크의 `autoExtend`는 Redisson native watchdog이 아니라 bluetape4k 공통 extender를 뜻합니다.

## 릴리스 소스

- [`leader-redis-lettuce/README.ko.md`](../../../../leader-redis-lettuce/README.ko.md)
- [`leader-redis-redisson/README.ko.md`](../../../../leader-redis-redisson/README.ko.md)
- [`docs/benchmarks/2026-06-01-issue-422-redis-lease-extension-throughput.json`](../../../benchmarks/2026-06-01-issue-422-redis-lease-extension-throughput.json)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [백엔드 선택](../guides/backend-selection.md)
- [리스 연장](../core/lease-extension.md)

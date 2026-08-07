---
title: "Hazelcast와 ZooKeeper"
description: "TTL 기반 map 소유권과 session 기반 Curator recipe의 차이를 비교합니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Hazelcast와 ZooKeeper

TTL 기반 map 소유권과 session 기반 Curator recipe의 차이를 비교합니다.

## Hazelcast

Hazelcast 백엔드는 TTL을 지정한 `IMap.putIfAbsent`와 owner 조건부 `remove`를 사용하며 CP Subsystem을 요구하지 않습니다. 그룹 선출은 slot마다 별도 map key를 둡니다. 기존 Hazelcast 환경을 활용하기 좋지만 map backup과 cluster split 동작이 업무 위험에 맞는지 확인해야 합니다.

## ZooKeeper

ZooKeeper 백엔드는 Curator의 `InterProcessMutex`와 `InterProcessSemaphoreV2`를 사용합니다. 소유권은 `leaseTime`에서 계산한 TTL이 아니라 session에 묶입니다. Curator 소유권이 thread에 민감하므로 coroutine 단일 리더 구현은 한 호출 동안 같은 전용 thread에서 획득과 해제를 수행합니다.

## 선택 규칙

이미 data-grid cluster를 운영하고 명시적 TTL 의미가 필요하면 Hazelcast를 선택합니다. session 기반 coordination과 Curator 운영에 익숙하다면 ZooKeeper가 맞습니다. 정상 경쟁뿐 아니라 cluster partition과 session expiry를 시험합니다.

## 릴리스 소스

- [`leader-hazelcast/README.ko.md`](../../../../leader-hazelcast/README.ko.md)
- [`leader-zookeeper/README.ko.md`](../../../../leader-zookeeper/README.ko.md)
- [`examples/cache-warmer/README.ko.md`](../../../../examples/cache-warmer/README.ko.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [백엔드 선택](../guides/backend-selection.md)
- [리더 선출 테스트](../guides/testing.md)

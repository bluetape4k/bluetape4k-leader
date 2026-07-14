---
title: "백엔드 선택"
description: "이미 운영하는 인프라를 우선하고, 소유권·시계·장애 의미를 비교해 최종 선택합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 백엔드 선택

이미 운영하는 인프라를 우선하고, 소유권·시계·장애 의미를 비교해 최종 선택합니다.

![Leader 백엔드 선택 지도](../../assets/backends/backend-selection-map.png)

## 선택 순서

먼저 애플리케이션이 이미 의존하는 내구성 저장소가 무엇인지 봅니다. Redis는 지연이 짧은 범용 선택입니다. Exposed JDBC/R2DBC는 선출 상태를 애플리케이션 SQL 데이터와 함께 운영할 수 있습니다. MongoDB와 DynamoDB는 문서·key-value 환경에 어울립니다. etcd, Consul, Kubernetes Lease, ZooKeeper는 control-plane 조정에 적합하고, Hazelcast는 CP Subsystem 없이 기존 Hazelcast cluster를 활용할 때 유용합니다.

## 반드시 비교할 의미

원자적 acquire와 소유자 조건부 release, 만료 시각의 기준, session 기반인지 TTL 기반인지, 그룹과 suspend 지원 여부, 자동 연장 지원 여부를 확인합니다. 상태 조회가 소유자와 만료 시각을 얼마나 정확히 보여 주는지도 봅니다. `runIfLeader()` API가 같아도 이런 차이가 운영 방식은 바꿉니다.

## 벤치마크만 보고 인프라를 고르지 않는다

저장소에 포함된 JMH 결과는 같은 머신에서 비교한 자료이지 모든 환경에 통하는 순위표가 아닙니다. 실제 환경에서는 네트워크, 내구성 설정, 연결 풀, 장애 복구가 더 큰 영향을 줍니다. 운영 적합성으로 후보를 좁힌 뒤 자신의 작업과 환경에서 측정합니다.

## 릴리스 소스

- [`README.ko.md`](../../../../README.ko.md)
- [`benchmark/README.ko.md`](../../../../benchmark/README.ko.md)
- [`docs/benchmarks/2026-05-21-leader-cross-backend-baseline.md`](../../../benchmarks/2026-05-21-leader-cross-backend-baseline.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [Redis 백엔드](../backends/redis.md)
- [etcd, Consul, Kubernetes Lease](../backends/control-plane-leases.md)
- [리더 선출 테스트](testing.md)

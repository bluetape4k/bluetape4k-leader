---
title: "Bluetape4k Leader 매뉴얼"
description: "bluetape4k-leader 0.4.0으로 분산 리더 선출을 설계하고 운영하는 방법을 릴리스 소스에 맞춰 설명합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Bluetape4k Leader 매뉴얼

bluetape4k-leader 0.4.0으로 분산 리더 선출을 설계하고 운영하는 방법을 릴리스 소스에 맞춰 설명합니다.

![Leader 저장소와 학습 구조](../assets/overview/repository-learning-map.png)

## 백엔드보다 실행 규칙을 먼저 정한다

모든 인스턴스가 같은 작업을 볼 수 있지만 실제 실행은 하나 또는 정해진 수만 맡아야 할 때 리더 선출을 사용합니다. 이 매뉴얼은 Redis나 SQL 같은 저장소를 고르기 전에 실행 결과의 의미와 실패 경계를 먼저 설명합니다.

가장 중요한 규칙은 단순합니다. 다른 인스턴스가 리스를 보유해 선출되지 못한 것은 오류가 아닙니다. `runIfLeader()`는 선출되면 작업 결과를 돌려주고, 경쟁에서 밀리면 `null`을 반환합니다. 작업 자체가 `null`을 반환할 수 있다면 결과 API를 사용합니다.

## 권장 학습 순서

1. [시작하기](getting-started.md)에서 로컬 elector로 기본 계약을 확인합니다.
2. [단일·그룹·전략 선출](guides/election-model-selection.md) 가운데 업무에 맞는 모델을 고릅니다.
3. [블로킹·Future·가상 스레드·코루틴](guides/execution-model-selection.md) 중 애플리케이션 실행 방식과 맞는 API를 선택합니다.
4. 이미 운영 중인 인프라를 기준으로 [백엔드](guides/backend-selection.md)를 좁힙니다.
5. [리스 수명 주기](guides/lease-lifecycle.md)를 정한 뒤 [메트릭과 런북](guides/observability-and-operations.md)을 붙입니다.

## 할 일에 맞춰 바로 찾기

- 공통 계약은 [Leader 핵심 라이브러리](modules/bluetape4k-leader-core.md)에서 익힙니다.
- Redis를 쓴다면 [Lettuce](modules/bluetape4k-leader-redis-lettuce.md)와 [Redisson](modules/bluetape4k-leader-redis-redisson.md)을 비교합니다.
- SQL 기반 선출은 [Exposed JDBC](modules/bluetape4k-leader-exposed-jdbc.md)와 [Exposed R2DBC](modules/bluetape4k-leader-exposed-r2dbc.md) 가운데 실행 방식에 맞는 쪽을 고릅니다.
- 프레임워크 연동은 [Spring Boot](modules/bluetape4k-leader-spring-boot.md), [Ktor](modules/bluetape4k-leader-ktor.md), [Micrometer](modules/bluetape4k-leader-micrometer.md) 문서에서 시작합니다.
- 완성된 흐름부터 보고 싶다면 [배치 스케줄러](modules/batch-scheduler.md), [마이그레이션 gate](modules/migration-gate.md), [Prometheus dashboard](modules/prometheus-dashboard.md) 예제를 따라갑니다.

## 이 매뉴얼의 버전 경계

모든 설명과 소스 링크는 `0.4.0` 릴리스의 커밋 `17ab7f872c1f96318c73d3580729cac20a67e017`을 기준으로 합니다. `examples/*`는 실행해 보는 학습 프로젝트이며 배포되는 라이브러리가 아닙니다. 애플리케이션에서는 보통 `io.github.bluetape4k:bluetape4k-dependencies`만 버전으로 관리하고 개별 Leader 모듈에는 버전을 적지 않습니다.

## 릴리스 소스

- [`README.ko.md`](../../../README.ko.md)
- [`leader-core/README.ko.md`](../../../leader-core/README.ko.md)
- [`settings.gradle.kts`](../../../settings.gradle.kts)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](index.md)
- [학습 경로](guides/learning-path.md)
- [실행 구조](architecture/runtime-model.md)

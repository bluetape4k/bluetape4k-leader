---
title: "스케줄 작업 마이그레이션"
description: "멱등한 작업에 선출 경계를 더하되, 이를 durable scheduling으로 오해하지 않습니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 스케줄 작업 마이그레이션

멱등한 작업에 선출 경계를 더하되, 이를 durable scheduling으로 오해하지 않습니다.

## 감싸기 전에

작업에 안정된 논리 이름을 주고 외부 효과를 멱등하게 만듭니다. 진행 상태를 durable하게 기록하고, 실행을 놓쳤을 때 건너뛸지 재시도할지 backfill할지 정합니다. 리더 선출은 동시에 들어온 시도만 조정하며 스케줄을 저장하지 않습니다.

## 경계와 시간 설정

상호 배제가 필요한 구간만 `runIfLeader` 안에 둡니다. 안전하다면 준비 작업은 밖에서 수행합니다. 측정한 실행 시간으로 리스를 정하고 외부 쓰기에 idempotency key를 넣습니다. 한 번의 실행이 리스보다 길 수 있다면 지원되는 연장을 사용하거나 작업을 나눕니다.

## 점진적 배포

처음에는 메트릭을 켜고 파괴적인 정리 작업은 제외한 채 배포합니다. 스케줄마다 elected가 한 번인지, 나머지 노드의 skip이 예상 범위인지, 실행 시간이 제한 안에 드는지, 해제가 정상인지 확인합니다. leader 강제 종료도 연습합니다. Quartz나 Spring Batch처럼 durable scheduler를 이미 쓴다면 복구 의미는 그대로 유지하고, 인스턴스 간 배제가 필요한 지점에만 선출을 덧붙입니다.

## 릴리스 소스

- [`examples/batch-scheduler/README.ko.md`](../../../../examples/batch-scheduler/README.ko.md)
- [`examples/migration-gate/README.ko.md`](../../../../examples/migration-gate/README.ko.md)
- [`examples/ktor-app/README.ko.md`](../../../../examples/ktor-app/README.ko.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [리스 수명 주기](lease-lifecycle.md)
- [Spring Boot와 Ktor 선택](spring-vs-ktor.md)
- [리더 선출 테스트](testing.md)

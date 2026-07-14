---
title: "Ktor 연동"
description: "애플리케이션 범위의 suspend elector를 설치하고 주기적 선출 시도를 Ktor 종료 수명 주기에 묶습니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Ktor 연동

애플리케이션 범위의 suspend elector를 설치하고 주기적 선출 시도를 Ktor 종료 수명 주기에 묶습니다.

## Plugin

`LeaderElectionPlugin`은 구성한 `SuspendLeaderElector`를 Ktor application에서 사용할 수 있게 합니다. 어떤 backend client와 elector를 소유할지는 애플리케이션이 정합니다. plugin 종료와 별도로 닫는 client가 경쟁하지 않도록 수명 주기 owner를 하나로 둡니다.

## Scheduling

`Application.leaderScheduled(lockName, period) { ... }`는 주기적으로 선출을 시도합니다. 선출된 인스턴스만 body를 실행하며 `ApplicationStopped`에서 job을 취소합니다. 리스는 정상 iteration 하나보다 길게 잡고, 실행 시간이 흔들린다면 지원되는 연장 전략을 사용합니다.

## 애플리케이션 책임

helper는 놓친 schedule을 저장하거나 retry를 직렬화하거나 외부 효과를 멱등하게 만들지 않습니다. 복구해야 하는 실행이라면 durable 작업 상태를 기록합니다. 같은 백엔드에 Ktor application 두 개를 연결해 단일 실행과 종료 시 취소를 함께 검증합니다.

## 릴리스 소스

- [`leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt)
- [`leader-ktor/README.ko.md`](../../../../leader-ktor/README.ko.md)
- [`examples/ktor-app/src/test/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppTest.kt`](../../../../examples/ktor-app/src/test/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppTest.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [Spring Boot와 Ktor 선택](../guides/spring-vs-ktor.md)
- [스케줄 작업 마이그레이션](../guides/scheduled-job-migration.md)

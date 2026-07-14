---
title: "Spring Boot와 Ktor 선택"
description: "기능 개수가 아니라 호스트 프레임워크의 호출 방식과 수명 주기에 맞춰 연동 모듈을 고릅니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Spring Boot와 Ktor 선택

기능 개수가 아니라 호스트 프레임워크의 호출 방식과 수명 주기에 맞춰 연동 모듈을 고릅니다.

![프레임워크와 관측 흐름의 소유권 경계](../../assets/frameworks/framework-observability-flow.png)

## Spring Boot

elector 자동 구성과 `@LeaderElection`, `@LeaderGroupElection`을 이용한 선언형 보호가 필요하면 Spring 모듈을 사용합니다. 0.4.0은 proxy 방식이 아니라 AspectJ compile-time weaving을 사용합니다. 동기, suspend, Mono, Flux, Flow 반환형을 지원하되 stream에는 별도의 리스 규칙이 있습니다.

## Ktor

서비스가 coroutine scheduling을 직접 소유하고 애플리케이션 범위의 `SuspendLeaderElector`를 쓰려면 Ktor plugin을 사용합니다. `leaderScheduled()`는 주기 작업을 Ktor 수명 주기에 묶고 `ApplicationStopped`에서 취소합니다.

## 경계

어느 연동도 리더 선출을 scheduler나 durable queue로 바꾸지는 않습니다. Spring annotation은 메서드 호출을 감싸고, Ktor helper는 주기적 coroutine을 띄웁니다. misfire 복구, durable 작업 상태, 재시도, 멱등성은 애플리케이션이나 별도의 job framework가 맡아야 합니다.

## 릴리스 소스

- [`leader-spring-boot/README.ko.md`](../../../../leader-spring-boot/README.ko.md)
- [`leader-ktor/README.ko.md`](../../../../leader-ktor/README.ko.md)
- [`examples/ktor-app/src/main/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppMain.kt`](../../../../examples/ktor-app/src/main/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppMain.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [Spring Boot 연동](../frameworks/spring-boot.md)
- [Ktor 연동](../frameworks/ktor.md)
- [스케줄 작업 마이그레이션](scheduled-job-migration.md)

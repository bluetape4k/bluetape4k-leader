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

선출기 자동 구성과 `@LeaderElection`, `@LeaderGroupElection`을 이용한 선언형 보호가 필요하면 Spring 모듈을 사용합니다. 0.4.0은 프록시 방식이 아니라 AspectJ 컴파일 타임 위빙을 사용합니다. 동기, 코루틴, `Mono`, `Flux`, `Flow` 반환형을 지원하되 스트림에는 별도의 리스 규칙이 있습니다.

## Ktor

서비스가 코루틴 주기 작업을 직접 관리하고 애플리케이션 범위의 `SuspendLeaderElector`를 쓰려면 Ktor 플러그인을 사용합니다. `leaderScheduled()`는 주기 작업을 Ktor 생명주기에 묶고 `ApplicationStopped`에서 취소합니다. 취소가 아닌 예외가 발생한 회차는 `WARN` 로그를 남기고 건너뛴 뒤 다음 주기를 계속합니다.

## 경계

어느 연동도 리더 선출을 스케줄러나 영속적인 대기열로 바꾸지는 않습니다. Spring 어노테이션은 메서드 호출을 감싸고, Ktor 도우미는 주기적으로 코루틴을 실행합니다. 놓친 실행의 복구, 영속적인 작업 상태, 재시도, 멱등성은 애플리케이션이나 별도의 작업 프레임워크가 맡아야 합니다.

## 릴리스 소스

- [`leader-spring-boot/README.ko.md`](../../../../leader-spring-boot/README.ko.md)
- [`leader-ktor/README.ko.md`](../../../../leader-ktor/README.ko.md)
- [`examples/ktor-app/src/main/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppMain.kt`](../../../../examples/ktor-app/src/main/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppMain.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [Spring Boot 연동](../frameworks/spring-boot.md)
- [Ktor 연동](../frameworks/ktor.md)
- [스케줄 작업 마이그레이션](scheduled-job-migration.md)

---
title: "Ktor 연동"
description: "애플리케이션 범위의 코루틴 선출기를 설치하고 주기적인 선출 시도를 Ktor 생명주기에 묶습니다."
releaseRef: 0.5.0
releaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor 연동

애플리케이션 범위의 코루틴 선출기를 설치하고 주기적인 선출 시도를 Ktor의 생명주기에 묶습니다.

## 플러그인

`LeaderElectionPlugin`은 구성한 `SuspendLeaderElector`를 Ktor 애플리케이션에서 사용할 수 있게 합니다. 어떤 백엔드 클라이언트와 선출기를 소유할지는 애플리케이션이 정합니다. 플러그인 종료와 별도로 클라이언트를 닫는 코드가 충돌하지 않도록 생명주기를 관리하는 주체는 하나로 둡니다.

## 주기 작업

`Application.leaderScheduled(lockName, period) { ... }`는 주기적으로 선출을 시도합니다. 선출된 인스턴스만 본문을 실행하며 `ApplicationStopped`에서 작업을 취소합니다. 취소가 아닌 `Exception`이 발생하면 `WARN` 로그를 남기고 해당 회차만 건너뛴 뒤 다음 주기를 계속합니다. 리스는 정상적인 한 회차보다 길게 잡고, 실행 시간의 편차가 크다면 지원되는 연장 전략을 사용합니다.

## 애플리케이션 책임

이 도우미는 놓친 실행 시각을 저장하거나 재시도를 순서대로 처리하거나 외부 효과를 멱등하게 만들지 않습니다. 복구해야 하는 실행이라면 영속적인 작업 상태를 기록합니다. 같은 백엔드에 Ktor 애플리케이션 두 개를 연결해 한 인스턴스에서만 실행되는지와 종료할 때 취소되는지를 함께 검증합니다.

## 릴리스 소스

- [`leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt`](../../../../leader-ktor/src/main/kotlin/io/bluetape4k/leader/ktor/LeaderElectionPlugin.kt)
- [`leader-ktor/README.ko.md`](../../../../leader-ktor/README.ko.md)
- [`examples/ktor-app/src/test/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppTest.kt`](../../../../examples/ktor-app/src/test/kotlin/io/bluetape4k/leader/examples/ktor/KtorAppTest.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [Spring Boot와 Ktor 선택](../guides/spring-vs-ktor.md)
- [스케줄 작업 마이그레이션](../guides/scheduled-job-migration.md)

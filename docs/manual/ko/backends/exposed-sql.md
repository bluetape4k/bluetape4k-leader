---
title: "Exposed SQL 백엔드"
description: "블로킹 서비스에는 JDBC를, coroutine-native SQL에는 R2DBC를 사용하고 선출 transaction은 짧게 유지합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# Exposed SQL 백엔드

블로킹 서비스에는 JDBC를, coroutine-native SQL에는 R2DBC를 사용하고 선출 transaction은 짧게 유지합니다.

## 모듈 구분

`leader-exposed-core`는 공통 table과 SQL 개념을 정의합니다. `leader-exposed-jdbc`는 JDBC data source를 위한 블로킹·async elector를, `leader-exposed-r2dbc`는 Exposed R2DBC 기반 suspend elector를 제공합니다. H2, PostgreSQL, MySQL 예제가 있습니다.

## 운영 모델

선출 row에는 owner와 expiry metadata가 저장됩니다. acquire와 release는 짧은 DB 연산이며 업무 로직 전체를 감싸는 transaction이 아닙니다. 작업은 선출 transaction 밖에서 실행하고 contender가 몰릴 때를 고려해 connection pool을 준비합니다. 시계 일관성이 중요하면 DB time 사용을 우선합니다.

## 선택 규칙

서비스와 transaction stack이 블로킹이면 JDBC를 사용합니다. 호출 경로가 coroutine-native이고 블로킹 bridge를 피해야 하면 R2DBC를 선택합니다. SQL 지연은 네트워크와 container 구성에 민감하므로 저장소의 H2 결과는 로컬 SQL layer baseline으로만 해석합니다.

## 릴리스 소스

- [`leader-exposed-core/README.ko.md`](../../../../leader-exposed-core/README.ko.md)
- [`leader-exposed-jdbc/README.ko.md`](../../../../leader-exposed-jdbc/README.ko.md)
- [`leader-exposed-r2dbc/README.ko.md`](../../../../leader-exposed-r2dbc/README.ko.md)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [백엔드 선택](../guides/backend-selection.md)
- [리더 선출 테스트](../guides/testing.md)

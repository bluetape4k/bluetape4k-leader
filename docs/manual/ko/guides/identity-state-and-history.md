---
title: "식별자, 상태, 이력"
description: "감사 식별자, 물리 노드 식별자, snapshot, 이벤트, 이력이 답하는 질문을 구분합니다."
releaseRef: 0.4.0
releaseCommit: 17ab7f872c1f96318c73d3580729cac20a67e017
---

# 식별자, 상태, 이력

감사 식별자, 물리 노드 식별자, snapshot, 이벤트, 이력이 답하는 질문을 구분합니다.

## 식별자를 섞지 않는다

`LeaderLease.auditLeaderId`는 선출 시점의 감사 식별자로, fencing token이나 백엔드 holder id일 수 있습니다. `nodeId`는 백엔드가 따로 추적할 때의 물리 프로세스 식별자입니다. 물리 노드 id를 증가하는 fencing token처럼 비교하면 split-brain 위험이 생깁니다.

## 상태와 이벤트

`state()`와 group state는 조회 시점에 확인할 수 있는 정보로 만든 snapshot입니다. listener와 hot event Flow는 elected, revoked, skipped 전이를 관측용으로 제공합니다. 원자적 acquire를 대신하지 않으며 정확한 만료 시각이 없을 수도 있습니다.

## 이력

history sink는 acquired, completed, failed 기록을 남깁니다. `EXPIRED`는 acquired 상태의 `lockedUntil`이 현재보다 과거일 때 읽는 시점에 계산하며, v1에서는 sweeper가 저장하지 않습니다. 이력 recorder 오류는 보호할 업무 로직과 격리되므로 sink 자체 상태를 별도로 감시해야 합니다.

## 릴리스 소스

- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLease.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderLease.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderState.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/LeaderState.kt)
- [`leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatus.kt`](../../../../leader-core/src/main/kotlin/io/bluetape4k/leader/history/LeaderHistoryStatus.kt)

## 이어서 읽기

- [Bluetape4k Leader 매뉴얼](../index.md)
- [관측과 운영](observability-and-operations.md)
- [Micrometer 연동](../frameworks/micrometer.md)

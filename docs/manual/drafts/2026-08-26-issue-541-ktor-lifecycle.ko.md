---
title: "Ktor plugin lifecycle과 graceful shutdown"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor plugin lifecycle과 graceful shutdown

> Issue #541와 Epic #701을 위한 unreleased 초안입니다. pinned `0.5.0` 매뉴얼에는 `sourceReleaseCommit`에 존재하는 API만 남깁니다.

`LeaderElectionPlugin`을 설치하면 애플리케이션 소유 resource 경계가 생깁니다.
`leaderScheduled`가 반환한 각 Job은 그 경계에 등록됩니다. `ApplicationStopped`에서
registry는 닫힘을 표시하고 등록된 Job을 즉시 취소한 뒤 자체 cleanup dispatcher에서
bounded join을 수행하므로 Ktor stop callback을 block하지 않습니다. 정리는 idempotent이며
전달받은 `SuspendLeaderElector`, Redis/SQL/Mongo client 또는 애플리케이션이 소유한 다른
backend를 닫지 않습니다.

Plugin 없이 explicit elector를 전달해 `leaderScheduled`를 호출하면 Job은 기존
Application scope를 사용하고 취소 책임은 caller에게 있습니다.

승격 전에는 acquire와 action 중 `ApplicationStopped` 취소를 deterministic하게 검증하고
정상 contention-null 계약도 확인해야 합니다.

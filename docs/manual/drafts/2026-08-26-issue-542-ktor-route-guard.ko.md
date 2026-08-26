---
title: "Ktor route-scoped leader guard"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor route-scoped leader guard

> Issue #542와 Epic #701을 위한 미배포 초안입니다. release-pinned 매뉴얼은 `sourceReleaseCommit`에 존재하는 API만 담습니다.

`Route.leaderGuard`(짧은 표기 `leaderOnlyRoute`)는 안정적인 leader-election 오류
계약을 route에 적용합니다. Ktor 공개 `AuthenticationChecked` hook 이후에 실행되므로
바깥의 `authenticate(...)` route와 애플리케이션이 소유한 authorization/rate-limit
plugin이 먼저 실행됩니다. 인증되지 않았거나 권한이 없는 요청은 leader backend에
도달하지 않습니다. 이 모듈은 `ktor-server-auth`를 `compileOnly`로만 참조하므로
`authenticate`를 사용하는 애플리케이션이 일치하는 Ktor authentication artifact를
제공해야 합니다.

```kotlin
routing {
    authenticate("service") {
        leaderGuard("projection-refresh") {
            get { call.respondText("ok") }
        }
    }
}
```

기본 authority mode는 `STATE`입니다. 요청마다 현재 `LeaderState`를 정확히 한 번
읽고 Empty이면 `NOT_LEADER`(503)를 반환합니다. 이는 passive한 현재 상태 기준값이므로
요청을 예약하거나 lease를 연장하지 않으며 downstream 작업의 원자성을 보장하지
않습니다. 기본 elector는 `supportsAuditLeaderState`를 광고해야 하며 다른 source에서
현재 상태 기준값을 공급해야 하면 명시적인 `stateProvider`를 사용하세요. 메서드 실행의
원자성이 필요하면 `@LeaderElection`을 사용합니다.

요청 자체가 lease를 보유해야 할 때만
`authorityMode = LeaderRouteAuthorityMode.LEASE`를 선택하세요. 이 mode는 명시적이며
`STATE`로 조용히 강등되지 않습니다. 명시적인 `SuspendLeaderLeaseAcquirer` 또는 해당
capability를 제공하는 elector가 필요하고 `leaseMaxDuration`은 유한한 양수여야 합니다.
Acquire와 release는 이 제한 시간으로 bounded하게 실행됩니다. 경쟁으로 lease를 얻지
못하면 `LEADER_LOCKED`(423)를 반환하고 성공한 요청은 정확히 한 번 release합니다.
Release 실패나 timeout은 로그에 남기되 downstream 응답이나 cancellation을 바꾸지
않습니다.

Guard 오류는 기본적으로 `lockName`과 leader metadata를 숨깁니다. 신뢰된 경계에서
의도적으로 필요할 때만 `exposeMetadata = true`를 사용하세요. 사용자 정의 status와
metadata 정책도 typed allow-list인 `LeaderElectionErrorResponder` 계약을 우회하지
않습니다.

승격 전에는 인증 순서, STATE, LEASE, release, metadata, cancellation을 의도한 Ktor
route pipeline에서 검증해야 합니다.

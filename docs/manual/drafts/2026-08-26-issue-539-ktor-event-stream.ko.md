---
title: "Ktor leader event stream"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor leader event stream

> Issue #539와 Epic #701을 위한 unreleased 초안입니다. 이 API는 `sourceReleaseCommit`보다 새로 추가되어 pinned `0.5.0` 매뉴얼은 수정하지 않았습니다. Event stream이 포함된 commit을 release manifest가 가리킨 뒤에만 이 초안을 승격하세요.

## 설치와 route 소유권

Event stream은 기본적으로 비활성입니다. 설정한 elector가
`LeaderElectionEventPublisher`도 구현할 때만 활성화하고, caller의 인증 또는 별도
authorization route 안에서 `leaderElectionEventStream()`을 명시적으로 등록하세요.
Plugin은 인증되지 않은 root route를 자동으로 만들지 않습니다.

```kotlin
install(SSE)
install(WebSockets) // WebSocket transport를 켤 때만

install(LeaderElectionPlugin) {
    leaderElection = listeningElector // SuspendLeaderElector + LeaderElectionEventPublisher
    eventStreamRouteEnabled = true
    eventStreamSseEnabled = true
    eventStreamWebSocketEnabled = true
}

routing {
    authenticate("operations") {
        leaderElectionEventStream()
    }
}
```

`ktor-server-sse`와 `ktor-server-websockets`는 compile-only optional dependency입니다.
일치하는 Ktor plugin과 artifact는 애플리케이션이 직접 제공합니다. SSE는
`eventStreamRoutePath`(기본값 `/management/leaderElection/events`)를 사용하고 WebSocket은
그 path 뒤에 `/ws`를 붙입니다. `eventStreamAllLocksEnabled = true`가 아니면
`lockName` query parameter가 필요하며, all-lock은
`eventStreamExposeLockName = true`도 요구합니다.

## Replay, payload, bounded 경계

Hub는 `Elected`, `Revoked`, `Skipped` 이벤트에 증가하는 `sequence`를 부여하고 SSE는 이를
event id로 보냅니다. `afterSequence` 또는 SSE `Last-Event-ID` 중 하나로 replay할 수 있지만
둘을 함께 보낼 수는 없습니다. 미래 cursor는 live-only로 시작하며 bounded ring보다 오래된
cursor에는 `replay_gap` control frame이 먼저 옵니다. `eventStreamReplayCapacity = 0`은
replay를 끄고 live 전달만 유지합니다. 잘못된 lock 이름과 cursor는 안정적인 400 오류
계약을 사용합니다.

기본 payload에는 lock 이름, leader metadata, `LeaderLease`, backend 주소가 없습니다.
신뢰된 consumer에서만 `eventStreamExposeLockName`과
`eventStreamExposeLeaderMetadata`를 opt-in하세요. Heartbeat payload는
`{"event":"heartbeat"}`입니다. Connection channel과 replay는 bounded이며 느린 consumer는
가장 오래된 item을 버립니다. `eventStreamMaxConnections`(1..1024)를 넘는 connection은
`BACKEND_UNAVAILABLE`(503)을 받습니다.

## Lifecycle과 승격 조건

Hub는 애플리케이션 소유 resource입니다. Shutdown은 collector와 모든 connection channel의
정리가 끝난 뒤에 plugin resource registry가 완료를 보고하도록 기다립니다. Caller가 소유한
elector, publisher, backend는 닫지 않습니다.

승격 전에 인증된 SSE와 WebSocket test-host session, replay와 `replay_gap`, heartbeat,
중복 cursor 거부, connection admission, peer disconnect cleanup, optional classpath 격리,
그리고 exact release commit의 전체 `:bluetape4k-leader-ktor:test`를 확인하세요.

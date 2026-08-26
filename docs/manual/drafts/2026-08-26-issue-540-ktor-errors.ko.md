---
title: "Ktor 구조화 오류 계약"
locale: ko
status: unreleased
sourceReleaseRef: 0.5.0
sourceReleaseCommit: 721a9a3808f67489d2bdb8177734325981c24977
---

# Ktor 구조화 오류 계약

> Issue #540와 Epic #701을 위한 unreleased 초안입니다. release-pinned 매뉴얼에는 `sourceReleaseCommit`에 존재하는 API만 남깁니다.

Management와 선택적인 `StatusPages` 처리는 converter가 없어도 같은 JSON 계약을
사용합니다. 정상적인 lock contention은 기존 `null`/skip으로 남으며 HTTP 오류가 아닙니다.

| Code | HTTP status | 의미 |
|---|---:|---|
| `INVALID_LOCK_NAME` | 400 | 비어 있거나 core lock 규칙을 벗어난 이름 |
| `NOT_LEADER` | 503 | 현재 leader 상태가 요청을 거부함 |
| `LEADER_LOCKED` | 423 | leader lock이 이미 점유됨 |
| `BACKEND_UNAVAILABLE` | 503 | backend/state 조회 실패 |
| `CONFIGURATION`, `INTERNAL` | 500 | 설정 오류 또는 예상하지 못한 요청 실패 |
| `INVALID_CURSOR` | 400 | 잘못된 stream cursor |

응답은 typed `LeaderElectionErrorOverride`가 `lockName` 노출을 명시하지 않는 한
`code`, `message`, 숫자 `status`만 포함합니다. Backend message, stack trace, cause 상세는
정제한 cause type만 로그에 남기고 응답에 복사하지 않습니다. `CancellationException`은
다시 던집니다.

Management route는 `respondText`로 JSON을 쓰므로 `ContentNegotiation` 없이 동작합니다.
Ktor `StatusPages`를 사용하는 애플리케이션은 optional adapter를 명시적으로 설치할 수
있습니다.

```kotlin
import io.bluetape4k.leader.ktor.statuspages.leaderElectionErrors
import io.ktor.server.plugins.statuspages.StatusPages

install(StatusPages) {
    leaderElectionErrors()
}
```

Adapter는 `compileOnly`이므로 활성화한 애플리케이션이 일치하는 Ktor
`ktor-server-status-pages` artifact를 제공해야 합니다. 분리된 `leaderScheduled` 예외는
`WARN`으로 기록하고 실패한 회차만 건너뛴 뒤 다음 주기를 계속합니다.

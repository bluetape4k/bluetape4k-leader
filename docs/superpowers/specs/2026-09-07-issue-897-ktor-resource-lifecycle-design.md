# Issue #897 Ktor 공통 리소스 lifecycle 연결 설계

> 상태: Issue #897의 승인된 범위와 배포된 projects API를 기준으로 확정한 구현 설계.

## 1. 목적

`bluetape4k-leader-ktor`의 애플리케이션 종료 진입점을
`bluetape4k-ktor-core`의 `ApplicationResourceRegistry`에 연결한다. 공통
registry는 Ktor `ApplicationStopped` subscription과 application-owned 동기식
close action만 소유한다. Leader registry는 기존의 coroutine `Job` 취소,
bounded join, 비동기 resource 완료 대기, timeout 집계와 상세 shutdown report를
계속 소유한다.

## 2. 확인한 전제

- `bluetape4k-dependencies`는 `2.1.0-SNAPSHOT`을 사용하며
  `bt4k.bluetape4k.ktor.core` alias를 제공한다.
- 해석된 `bluetape4k-ktor-core:2.1.0-SNAPSHOT` JAR에는
  `Application.installApplicationResourceLifecycle()`와
  `ApplicationResourceRegistry.register(AutoCloseable)`가 공개되어 있다.
- 공통 registry의 close action은 caller thread에서 동기적으로 실행되고 timeout,
  dispatcher, coroutine scope를 만들지 않는다.
- Leader registry의 `close()`는 entry를 원자적으로 claim한 뒤 job을 즉시 취소하고
  실제 cleanup을 전용 scope에 예약하므로 공통 registry의 bounded 동기식 action
  계약을 만족한다.

## 3. 선택한 구조

`LeaderElectionPlugin` 설치 시 다음 순서로 소유권을 구성한다.

1. `application.installApplicationResourceLifecycle()`로 application 공통 registry를
   설치하거나 기존 인스턴스를 재사용한다.
2. `LeaderElectionResourceRegistryImpl`을 만들고 shutdown observer를 즉시 등록한다.
3. Leader registry 자체를 공통 registry에 `AutoCloseable`로 한 번 등록한다.
4. scheduler job과 event hub는 기존처럼 Leader registry에 등록한다.
5. `ApplicationStopped`에서는 공통 registry가 Leader registry의 `close()`만 호출한다.
   Leader cleanup의 완료 대기와 report 확정은 Leader registry 내부에서 계속된다.

상태 전이는 다음과 같다.

```text
ApplicationResourceRegistry.OPEN
  -> ApplicationStopped
  -> DRAINING: LeaderElectionResourceRegistry.close() 호출
  -> CLOSED

LeaderElectionResourceRegistry.OPEN
  -> close(): entry claim + Job.cancel + async cleanup 예약
  -> cleanup 완료 또는 항목별 bounded timeout
  -> LeaderElectionShutdownReport 확정
```

## 4. 대안과 결정

### 채택: 공통 registry가 Leader adapter의 close 시작점만 소유

중복 Ktor lifecycle subscription을 제거하면서 두 registry의 책임을 섞지 않는다.
공통 registry가 동기식 application ownership을 제공하고, Leader adapter가 domain
정책을 유지한다.

### 기각: Leader registry를 공통 registry로 완전히 교체

공통 registry에는 coroutine job join, resource 완료 대기, timeout, cancellation
precedence와 Leader 전용 report가 없다. 교체하면 공개되지는 않았어도 이미 검증된
shutdown 계약이 축소된다.

### 기각: 두 registry가 각각 `ApplicationStopped`를 구독

동작은 idempotent일 수 있지만 lifecycle owner가 둘이 되어 호출 순서와 중복 close
근거가 불명확해진다. 이번 adoption의 목적과 맞지 않는다.

### 기각: Leader의 async cleanup을 공통 registry close action에서 기다림

Ktor event callback을 block하고 공통 registry의 bounded 동기식 action 계약을
위반한다. timeout과 dispatcher 정책도 잘못된 계층으로 이동한다.

## 5. 실패·경합 계약

- 공통 registry 또는 Leader registry의 `close()`를 반복해도 resource는 한 번만
  claim된다.
- Leader resource의 close callback이 registry close에 재진입해도 lock을 잡은 채
  user code를 실행하지 않으므로 deadlock이나 중복 close가 발생하지 않는다.
- application 종료 뒤 Leader registry에 등록한 resource와 job은 보관하지 않고
  즉시 close 또는 cancel한다.
- bounded await가 timeout이어도 drained entry는 registry에서 제거된 상태이며 이후
  등록이 즉시 정리되어 종료한 resource를 다시 application ownership에 넣지 않는다.
- 공통 lifecycle 설치 또는 registration이 실패하면 plugin 설치를 실패시키며 독립
  `ApplicationStopped` fallback을 추가하지 않는다.
- Leader cleanup 실패와 timeout은 기존 `LeaderElectionShutdownReport` 필드를
  유지한다. 공통 registry report로 축소하거나 원본 예외를 노출하지 않는다.

## 6. 호환성과 dependency 경계

- `LeaderElectionPlugin`, `leaderScheduled`와 공개 설정의 source/JVM API는 바꾸지
  않는다.
- 신규 dependency는 구현 세부사항이므로 `implementation(bt4k.bluetape4k.ktor.core)`로
  둔다. 공개 시그니처에는 projects 타입을 노출하지 않는다.
- `leader-ktor` publication POM에는 runtime dependency가 포함되어야 하며 버전은
  catalog/BOM 경로에서 해석한다. 임시 repository나 artifact override는 추가하지 않는다.
- 과거 lesson의 "production `ktor-core` runtime dependency 없음" 결정은 이번에
  실제 공통 lifecycle API를 사용하는 조건부 예외로 갱신한다.

## 7. 완료 조건

- 공통 registry가 application stop에서 Leader registry를 정확히 한 번 닫는다.
- 중복 close, late registration, reentrant close, timeout 뒤 등록 경로 테스트가
  deterministic하게 통과한다.
- 기존 bounded job await와 async resource cleanup 테스트가 통과한다.
- `leader-ktor` 전체 테스트, detekt, binary compatibility, dependency/POM 검증이
  통과한다.
- migration 결정과 유지한 domain 정책을 lesson에 기록한다.
- exact-head CI와 독립 코드 리뷰에서 P0/P1이 없다.

# Issue #897 - Ktor 공통 리소스 lifecycle 채택

## 맥락

`leader-ktor`는 scheduler job, event hub와 비동기 close resource를 정리하기 위해
Leader 전용 registry를 사용한다. projects #1656과 PR #1668은 Ktor application이
소유하는 bounded 동기식 리소스를 `ApplicationStopped`에 연결하는 공통
`ApplicationResourceRegistry`를 도입했다.

기존 #452 결정은 `leader-ktor`가 공통 production runtime 동작을 사용하지 않았기
때문에 `bluetape4k-ktor-core` dependency를 두지 않는 것이었다. 공통 lifecycle API가
배포된 뒤에는 Ktor stop subscription을 각 통합 모듈이 다시 구현할 이유가 없어졌다.

## 결정

- `leader-ktor`는 `bluetape4k-ktor-core`를 implementation dependency로 사용한다.
- 공통 registry에는 `LeaderElectionResourceRegistryImpl.close()`만 application-owned
  동기식 close action으로 등록한다.
- Leader registry의 job cancellation, bounded join, 비동기 resource await, timeout,
  lease cleanup과 상세 shutdown report는 이동하거나 축소하지 않는다.
- plugin 자체의 `ApplicationStopped` hook은 제거하고 공통 lifecycle을 단일 owner로
  사용한다.
- caller가 제공한 elector, Redis client, database pool과 HTTP client는 계속 닫지
  않는다.

## 결과

application lifecycle subscription과 동기식 close orchestration은 projects 공통
계약을 재사용한다. Leader의 `close()`는 entry를 먼저 claim하고 비동기 cleanup을
예약하므로 공통 registry callback을 block하지 않는다. 반복 close, 재진입, 종료 뒤
late registration과 cleanup timeout 뒤 registration도 기존 exactly-once 경계를
유지한다.

## 검증

- `LeaderElectionPluginTest`에서 공통 registry의 단일 Leader registration과
  application stop 연결을 확인한다.
- `LeaderElectionResourceRegistryTest`에서 재진입 close와 timeout 뒤 late
  registration을 확인한다.
- `:bluetape4k-leader-ktor:test`, detekt, binary compatibility와 publication POM으로
  최종 경계를 검증한다.

## 향후 지침

공통 Ktor lifecycle에는 caller thread에서 끝나는 application-owned 동기식 close
action만 등록한다. backend별 timeout, coroutine scope, lease release 또는 상세
진단을 공통 registry로 올리지 않는다. 새 Leader resource는 Leader registry에
등록하고 application stop ownership은 공통 registry 한 곳에서만 유지한다.

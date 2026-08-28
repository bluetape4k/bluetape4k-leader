# #804 전략적 후보 heartbeat가 결과 카운터를 되돌린 문제

## 문제

`CandidateInfo` 전체를 저장하는 `registerCandidate`를 heartbeat처럼 호출하면,
호출자가 들고 있던 오래된 후보 정보가 Redis의 최신 후보를 덮어쓴다. 그 결과
`successCount`, `failureCount`, `lastCompletionTime`이 최근 실행 전 상태로
되돌아가고, 점수 기반 선출 순서도 달라질 수 있다.

## 수정

- `registerCandidate`의 전체 교체 의미는 호환성을 위해 유지한다.
- 네 가지 strategic 인터페이스에 `refreshCandidate`를 추가한다.
- `refreshCandidate`는 등록·실행·결과 필드를 보존하고 `metadata`와 요청한 TTL만
  갱신한다.
- Lettuce는 GET과 갱신을 하나의 Redis script로 묶고, Redisson은 후보별 entry lock을
  `refreshCandidate`와 `updateResult`가 함께 사용하게 한다. Local 네 구현은
  `ConcurrentHashMap.computeIfPresent`로 같은 키의 갱신을 원자화한다.
- 이미 만료된 Redis 후보를 refresh해서 새 후보가 생기지 않도록 한다. 최초 등록은
  `registerCandidate`가 담당한다.
- `updateResult`는 기존 TTL을 유지하고, heartbeat가 명시한 TTL만 후보 생존 시간을
  다시 설정한다.

## 예방 규칙

실패한 가정: 후보 등록을 반복하면 최신 `CandidateInfo`가 자동으로 병합된다.

발견 증거 또는 교정: 등록은 전체 교체이고, 결과 갱신은 카운터를 원자적으로
증가시키므로 오래된 등록 정보가 최신 결과를 덮어쓸 수 있었다.

수정 결정: 초기 등록과 heartbeat를 `registerCandidate`/`refreshCandidate`로 분리하고,
Redis backend에서는 결과 필드 보존을 저장소 원자 경계로 강제했다.

향후 예방 확인: 새 후보 생존 갱신 API를 추가할 때는 blocking·suspend single/group의
두 호출 순서, TTL 보존·재설정, 만료 후보 no-op, 점수 순서, update/unregister와의
동시 실행을 함께 검증한다. Local 구현에서 `listCandidates` 후 `registerCandidate`를
다시 조합하지 말고 후보 키의 `computeIfPresent` 경계를 유지한다.

## 검증

- Lettuce strategic heartbeat: 5 passing
- Redisson strategic heartbeat: 5 passing
- 각 테스트는 blocking/suspend single/group과 refresh 전후 결과 갱신 순서를 포함한다.
- `updateResult` 후 TTL 보존과 `refreshCandidate` 후 TTL 재설정은 Lettuce `PTTL`과
  Redisson `remainTimeToLive`로 확인한다.

# #924 비동기 정리는 native future를 우선한다

## 결정과 계약

Redisson 4.7.0 dependencyInsight 및 source JAR의 RLockAsync/RKeysAsync를 확인했다.
동기 ownership 조회와 common-pool supplyAsync 안의 key expiry는 정리 호출자나
공용 worker를 Redis I/O에 묶는다. 기존 native async API를 연결하고 거부 후 정리도
같은 expiry 경로를 사용한다. 별도 executor나 의존성은 추가하지 않는다.

획득 threadId와 monotonic 최소 lease 계산, watchdog 종료 순서, action 결과의
우선순위는 유지한다. single/group의 정리 future 실패는 로그 관측 후 기존 action
결과를 유지한다. group permit 갱신과 single key expiry는 다른 연산이므로 통합하지 않는다.
ownership 조회와 expiry의 원자성을 새로 보장하는 변경은 아니다.

## 테스트 재발 방지

완료되지 않은 ownership/expiry future를 barrier로 두고 결과가 너무 일찍
완료되지 않는지 검사한다. 동기 Redis 호출은 테스트에서 즉시 실패하게 만든다.
처음에는 전역 PER_CLASS 설정을 놓쳐 완료된 future가 다음 테스트에 재사용됐다.
상태를 가진 fixture에는 PER_METHOD 또는 명시적 초기화를 적용하고 기존 코드에서
RED를 다시 확인해야 한다. 이번에는 원본 구현에서 의도한 3개 실패를 재확인한 뒤
수정 구현에서 single/group 정책을 포함한 10개 테스트가 통과했다.

## 검토 범위

직접 코드·설계 검토 대체는 독립 reviewer PASS가 아니다. public signature는
변경하지 않았다. async 호출에서 모든 Redis I/O가 사라졌다고 확대 해석하지 않는다.
group 초기화·audit, 동기 API는 이번 변경 대상이 아니며 native 정리 future 범위만 검증한다.

SPW-01~05: 한국어, live issue·해석 버전·source·RED/GREEN 근거, 정책과 한계
분리, README 두 언어와 계획의 일치 확인.

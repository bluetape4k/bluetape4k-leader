# #918: 취소 요청은 종료 완료가 아니다

## 실패한 가정과 수정

기존 두 예제는 isActive=false를 재시작 가능 상태로 보고 stop의 finally에서 job 참조를 지웠다.
NonCancellable cleanup barrier를 둔 runTest에서 timeout·stop 호출자 취소·직접 취소 중
start가 성공하여 가정이 틀렸음을 확인했다. 0 timeout에서는 취소 블록조차 실행되지 않았다.
두 모듈 각각 4개 회귀가 실패했고 callback에서 새 job을 시작하는 기존 참조 보호 경계는 통과했다.

기존 lifecycleLock에서 isCompleted를 검사하고, job을 저장한 뒤 invokeOnCompletion으로
동일 job 참조만 지우도록 수정했다. 이미 완료된 job에도 callback이 즉시 실행될 수 있으므로
등록보다 대입이 먼저여야 한다. stop은 취소를 먼저 요청하고 bounded join을 수행한다.
timeout은 경고이며 정리 완료나 분산 lease 해제를 보장하지 않는다. Unit API는 유지한다.

## 검증과 재발 방지

- 최종 전체 테스트: webhook-poller 19, tenant-aggregator 22 PASS; 두 모듈 detekt PASS.
- 새로운 회귀는 각 6건: timeout, stop 호출자 취소, 직접 취소, 0 timeout,
  이전 stop 반환과 새 job 참조 경합, 이미 취소된 scope의 즉시 완료.
- MongoDB 및 H2/PostgreSQL 기존 통합 검증을 순차 실행했다. library production/ABI/의존성 변경 없음.
- 같은 인스턴스의 로컬 lifecycle만 다룬다. 분산 lock 중복 획득이나 unlock 보장은 주장하지 않는다.
- 회귀는 동일한 cleanup barrier와 가상 시간을 사용하되 두 예제 때문에 새 전역 추상화를 만들지 않는다.
- native 독립 검토가 반복 실패한 세션이므로 직접 코드·설계 검토로 대체했다. 독립 PASS로 표기하지 않는다.

## 직접 검토

취소 전파, callback 등록 순서, 참조 비교, 정상/오류/timeout 종료, scope 소유권, README 영/한 및 KDoc을
검토했다. P0/P1=0, WATCH. 새로운 executor/scope/의존성은 production에 추가하지 않았다.
공개 반환형과 예외 유형은 유지하며, 기존 동시 start check-then-set 보호를 보존한다.
SPW-01~05/KO-01~07: 유지보수자 대상 한국어, #918·실행·diff 대조, 근거와 한계 구분, read-back 확인.

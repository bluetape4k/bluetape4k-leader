# #918 worker 종료·재시작 수정 계획

승인 범위: develop(ded0504b) → fix/issue-918-worker-restart, PR 생성까지. 머지·dispatch 제외.

1. [완료] 두 예제에 runTest·NonCancellable barrier 회귀를 추가했다. 각 4건 RED를 확인했다.
2. [완료] 기존 lifecycleLock, isCompleted, invokeOnCompletion 및 참조 비교로 수정했다. timeout 경고와 Unit API를 유지하며 0 timeout도 취소한다.
3. [완료] 완료 후 재시작, 이전 stop과 새 job 경합, 이미 취소된 scope까지 각 6건 회귀를 통과했다.
4. [진행 중] 전체 19/22건과 detekt PASS. README 영/한·KDoc·교훈과 직접 검토 완료. PR 생성 및 정확한 head CI는 다음 단계다.

새 전역 lifecycle helper나 의존성을 추가하지 않는다. Job/cancelAndJoin/invokeOnCompletion과 기존 ReentrantLock을 재사용한다.
분산 lock 중복 획득 문제로 확대하지 않고 같은 worker 인스턴스의 로컬 lifecycle 중첩만 수정한다.
NonCancellable은 회귀 테스트에서 cleanup을 멈추는 도구이며 production 종료를 무기한 기다리게 만들지 않는다.
실패 시 해당 테스트로 돌아가며, 되돌리기는 두 예제와 본 문서 diff로 제한한다.
SPW-01~05: 한국어 유지보수자 문서, #918 소스·기존 테스트·GNO 대조, 범위와 실패 처리, read-back 확인.

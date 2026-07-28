# 단원: AC-6 LockExtender 동시 스트레스 테스트

**날짜**: 2026-05-16 **문제**: #176 **PR**: TBD **영향을 받는 모듈**: `leader-core`(testFixtures)

## 요약

`AbstractSyncLockExtenderContractTest` 및 `AbstractSuspendLockExtenderContractTest`에 AC-6 동시 확장 스트레스 테스트를 추가했습니다. 테스트에서는 N 리더가 동시에 자체 잠금을 확장할 때 백엔드 확장 작업이 경합 없는지 검증합니다.

## 설계 결정: 작업자별 잠금 이름

`LockExtender`는 `ThreadLocal`(동기화)/`CoroutineContext.Element`(일시 중지)를 사용하여 활성 잠금 핸들을 호출 스레드/코루틴에 바인딩합니다. `runIfLeader` 내부에서 생성된 하위 스레드는 잠금 핸들을 상속하지 않습니다.

이는 "감시자 × 사용자 확장" 동시 시나리오가 계약 수준에서 공개 `LockExtender` API를 통해 테스트될 수 없음을 의미합니다. 대신 AC-6 테스트는 다음을 검증합니다.

**N개의 동시 리더, 각각 자체 슬롯을 확장** - 이는 독립 클라이언트의 동시 원자 확장 호출을 동시에 처리하는 백엔드의 기능을 실행합니다.

## SuspendedJobTester 의미론

`SuspendedJobTester.workers(N).rounds(M)`:
- `rounds(M)` = **M개의 총 호출**(N×M 아님)
- `workers(N)` = 최대 동시성 수준

이는 `MultithreadingTester.workers(N).rounds(M)` = N×M 총 호출과 다릅니다.

suspend 테스트에 대한 어설션: `rounds * extendsPerRound`(`workers * rounds * extendsPerRound` 아님).

## 추가된 테스트

### 동기화(AbstractSyncLockExtenderContractTest)
- `AC-6 concurrent extends race-free — N workers each extend their own lock`: `MultithreadingTester(8 workers × 10 rounds × 5 extends = 400)`
- `AC-6b sequential extends with random durations are all successful`: 20개의 연속 무작위 기간 확장

### 일시중단(AbstractSuspendLockExtenderContractTest)
- `AC-6 concurrent suspend extends race-free — N workers each extend their own lock`: `SuspendedJobTester(8 workers, 10 rounds × 5 extends = 50)`
- `AC-6b sequential suspend extends with random durations are all successful`: 20개의 연속 무작위 기간 확장

## 검증

```
:leader-core:test — BUILD SUCCESSFUL (all 687+ tests pass)
```

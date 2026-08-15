# Issue #673 timing 안정화 교훈

## 결정

동시성 테스트의 준비 상태는 고정된 `delay`나 무제한 polling으로 추정하지
않고, 관찰 가능한 상태를 bounded Awaitility assertion으로 기다린다. timeout
메시지는 lock name, backend, 현재 count를 보존해야 한다.

- Redisson group 슬롯 준비: `while`/`delay`를 `atMost(5.seconds)`와
  `untilAsserted`로 교체했다.
- `LeaderLeaseAutoExtender` 첫 stress case: 5초 고정 `delay`를 delegate 호출
  count의 bounded assertion으로 교체하고 watchdog을 `finally`에서 닫는다.
- async extend case: `Thread.sleep`과 600ms latch deadline 대신
  `CompletableFuture` gate를 사용한다. 세 delegate가 gate에서 동시에 시작된
  뒤 release되므로 serial scheduler는 2초 bounded assertion에서 진단된다.
- Local suspend skip case: 300ms wall-clock `delay`를 `CompletableDeferred`
  release gate로 교체해 wait-time contract만 검증한다.

## 검증 계약

`scripts/ci/validate_timing_contract.py`는 TEST-02의 세 대상 파일에서 무제한
polling, 고정 delay, `Thread.sleep`, 모호한 600ms latch deadline을 차단한다.
validator 자체는 `validate_timing_contract_test.py`로 clean/violation fixture를
검증하고 `ci-contract` job에서 실행한다.

관련 모듈의 targeted Gradle test와 반복 실행 결과는 PR 본문 및 `## DoD Status`
에 exact command와 함께 기록한다. CI job timeout은 기존 `ci-contract` 5분과
각 모듈 test job의 repository 설정을 유지하며, 새 테스트는 그 제한 안에서
bounded await가 실패하도록 한다.

이번 fresh 반복 검증에서는 core 대상 11개 테스트를 3회, Redisson group 대상
16개 테스트를 3회 실행해 모두 `BUILD SUCCESSFUL`이었고 flake failure는 각각
`0/3`이었다. core/Redisson `detekt`, 기존 CI contract와 timing validator,
`git diff --check`도 통과했다.

## 재발 방지

새 concurrency/lifecycle test는 준비 신호(`Channel`, `CompletableDeferred`,
`AtomicInteger`)를 먼저 노출하고, Awaitility timeout과 진단 가능한 assertion을
추가한다. 실제 backend watchdog을 검증하는 테스트는 `runSuspendIO`를 유지하고
`runTest`의 virtual time으로 scheduler thread를 가리지 않는다.

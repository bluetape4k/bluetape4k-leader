# Redisson suspend 취소 테스트의 acquisition race

## 결정

Redisson `SuspendLeaderGroupElector`의 `minLeaseTime` 취소 회귀 테스트에서
고정 `20ms` 대기 후 작업을 취소하던 방식을 action 진입 신호 기반으로
바꿉니다. `runIfLeader`가 Redis permit을 획득하기 전에 취소되면 두 번째
elector가 정상적으로 슬롯을 얻을 수 있으므로, 그 결과를
`NonCancellable` cleanup 실패로 해석하면 안 됩니다.

## 원인과 수정

Testcontainers Redis와 Redisson 초기화·네트워크 왕복 시간은 고정 대기보다
길어질 수 있습니다. 기존 테스트는 action 시작 여부를 확인하지 않아
acquisition 이전 취소 race를 허용했습니다. `CompletableDeferred`를 action
첫 줄에서 완료하고 `awaitCancellation()`으로 permit 보유 상태를 유지한 뒤
신호를 받은 호출자를 취소하도록 바꿨습니다. production 코드는 변경하지
않았습니다.

## 검증 증거

- 기존 테스트는 기준선에서 5회 중 4회 `Expected "should-not" to equal to
  <null>` assertion으로 실패했습니다.
- 신호 기반 테스트는 단일 실행과 5회 반복 모두 성공했습니다.
- Redisson 전체 모듈 테스트는 `213 tests, failures=0, errors=0, skipped=0`으로
  성공했습니다.
- `ABI_BASE_VERSION=0.5.0 ABI_CURRENT_VERSION=1.0.0 ...
  checkBinaryCompatibility`는 `artifacts=16, ignored=7, unknown=5` 기준선
  차이로 계속 실패했습니다. 해당 결과는 테스트 파일만 변경한 이 수정과
  무관한 별도 ABI/release-train 작업입니다.

## 다음 변경자에게 적용할 규칙

1. 분산 lock/permit 취소 테스트는 시간 지연으로 acquisition 완료를
   추정하지 말고 action 진입 또는 명시적 acquire signal을 기다립니다.
2. cancellation cleanup을 검증할 때는 취소 시점에 permit을 실제로 보유하고
   있는지 먼저 증명합니다.
3. 재현 실패가 production 경로에 없는 test-only race라면 production
   cleanup을 완화하거나 재시도하는 대신 테스트 동기화를 고칩니다.

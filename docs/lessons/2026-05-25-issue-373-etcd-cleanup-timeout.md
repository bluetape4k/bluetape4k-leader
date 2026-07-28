# 문제 373 etcd 정리 시간 초과 정책

## 맥락

Issue #373은 etcd 정리 경로에서 `10s` 대기가 수정된 것으로 추적되었습니다. 단일 리더 정리는 잠금 해제 및 리스 취소를 위해 하드 코딩된 예산을 사용했으며 차단 그룹 선출기에도 동일한 패턴이 존재했습니다.

## 결정

공개 옵션을 변경하지 않고 유지하고 기존 선택 설정인 `max(waitTime, retryDelay)`에서 내부 정리 시간 초과를 파생시킵니다. 이는 호출자가 `waitTime = 0`를 사용할 때 최소한 백엔드 재시도 지연으로 정리를 제한하면서 일반적인 경우에 대해 구성된 획득 예산을 유지합니다.

## 결과

단일 및 그룹 etcd 정리 차단은 이제 고정 `10s` 대기 대신 명명된 내부 시간 초과 도우미를 사용합니다. 단위 적용 범위는 단일 정리, 제로 대기 대체 정리 및 그룹 정리를 위해 `CompletableFuture.get`에 전달된 시간 초과를 기록합니다.

## 검증

- `git diff --check`
- `./gradlew :bluetape4k-leader-etcd:test --tests '*EtcdLeaderCleanupTimeoutTest' --tests '*EtcdLeaderElector*' --no-daemon` (9통과)
- `./gradlew :bluetape4k-leader-etcd:test --tests '*Etcd*' --no-daemon` (64통과)
- Claude 코드 검토 아티팩트: `.omx/artifacts/claude-issue-373-etcd-cleanup-timeout-20260525114258.md`(P0=0, P1=0)

## 퓨쳐 가드

정리 대기의 경우 익명의 고정 상수를 사용하지 마세요. 구성된 백엔드 예산이 있는 경우 이를 사용하거나 명명된 내부 정책을 도입하고 향후 차단에 전달된 정확한 시간 제한을 테스트하세요.

# 문제 514 - Redisson 비동기 릴리스 완료

## 맥락

Issue #514에서는 Redisson 비동기 선출 경로의 순서 버그가 노출되었습니다. 반환된 `CompletableFuture`는 잠금/허가 해제 또는 최소 리스 업데이트가 부작용으로 계속 실행되는 동안 사용자 작업에서 완료될 수 있습니다.

## 결정

호출자에게 표시되는 미래를 릴리스/업데이트 미래로 구성합니다. 외부 future는 작업과 릴리스/업데이트 경로가 모두 완료된 후에만 완료되어야 합니다. 작업이 실패한 경우 정리 후 해당 작업 실패를 유지합니다. 정리가 실패하면 결정적으로 기록합니다.

## 테스트 가드

주문 계약을 증명하기 위해 휴면 없이 즉시 재시도 테스트를 사용하세요. 첫 번째 호출이 완료된 후 두 번째 호출은 즉시 동일한 잠금 또는 그룹 허가를 획득해야 합니다. bluetape4k 동시성 민감 회귀의 경우 임시 `repeat` 루프 대신 `MultithreadingTester`를 사용하여 반복 스트레스를 표현합니다. 향후 생성 전 동기 동작 던지기와 생성 후 실패한 미래에 대해 별도의 테스트를 유지하세요.

## 문서 가드

비동기 완료 순서가 변경되면 릴리스, `minLeaseTime` 또는 비동기 실행 의미 체계를 설명하는 두 모듈 README 로케일 파일을 모두 업데이트하세요. 이번 호에서는 `README.ko.md`에도 오래된 Redisson `autoExtend` 문구가 있었고 `README.md`와 일치하도록 다시 가져왔습니다.

## 결과

Redisson 단일 잠금 및 그룹 비동기 경로는 이제 반환된 미래를 완료하기 전에 릴리스/업데이트를 기다립니다. 이렇게 하면 `join()` 또는 `get()`가 반환된 후 즉각적인 연결 재시도에서 잘못된 경합이 관찰되는 것을 방지할 수 있습니다.

## 검증

- 대상 Redisson 단일/그룹 테스트: `46 passing`, `BUILD SUCCESSFUL in 14s`.
- 전체 Redisson 모듈 테스트: `./gradlew :bluetape4k-leader-redis-redisson:test --no-parallel`, `204 passing`, `BUILD SUCCESSFUL in 18s`.
- 컴파일 경고 복구: 재실행 로그에 `Expression is unused`가 없습니다.
- 리뷰 게이트: 독립 `code-reviewer` P0/P1 = 0; 독립 `verifier` 수리 레인 P0/P1/P2/P3 = 0.
- `git diff --check`: 통과.

# Issue #228 - DynamoDB 리더 백엔드

## 맥락

Issue #228에는 차단, 비동기 파사드, 코루틴, 가상 스레드, 그룹 선택, Spring Boot 및 CI/야간 적용 범위를 갖춘 `leader-dynamodb` 백엔드가 추가되었습니다. 백엔드는 `lockName`를 파티션 키로, `leaseExpiry`를 논리적 정확성 경계로 사용하여 호출자 소유의 DynamoDB 테이블을 사용합니다.

## 결정 또는 발견

- DynamoDB `ttl`를 정리 메타데이터로만 처리합니다. 모든 정확성 검사는 `leaseExpiry`에 대한 조건부 쓰기를 사용해야 합니다.
- 고유한 `ownerId` 값을 펜싱 토큰으로 사용하고 `auditLeaderId`를 별도로 저장하여 슬롯 기반 `LeaderRunResult` 및 상태 API가 호출자에게 표시되는 리더 ID를 유지합니다.
- 그룹 상태 열거는 DynamoDB `Scan` 응답에 페이지를 매겨야 합니다. 첫 페이지 스캔은 테이블 응답이 DynamoDB의 페이지 경계를 넘으면 자동으로 상태를 자릅니다.
- 획득 일시 중지로 인해 AWS SDK 미래가 직접 취소되어서는 안 됩니다. 획득이 아직 진행 중인 동안 코루틴이 취소되면 대신 늦게 success한 획득을 해제하세요.

## 결과

이제 구현에는 페이지가 매겨진 그룹 상태 스캔, 후기 릴리스 보호 기능을 갖춘 취소 인식 내부 비동기 획득, 릴리스 전 워치독 닫기 격리, DynamoDB용 Spring Boot/AOP 공장 배선이 포함됩니다.

## 검증

- `./gradlew :bluetape4k-leader-dynamodb:test --no-daemon --console=plain`는 18가지 테스트를 통과했습니다.
- `./gradlew :bluetape4k-leader-spring-boot:test --tests 'io.bluetape4k.leader.spring.BackendConditionalTest' --tests 'io.bluetape4k.leader.spring.aop.autoconfigure.DynamoDbAopFactoryAutoConfigurationTest' --no-daemon --console=plain`는 18가지 테스트를 통과했습니다.
- `./gradlew build -x test -x k8sTest --no-daemon --console=plain`가 통과되었습니다.
- `actionlint .github/workflows/ci.yml .github/workflows/nightly-tests.yml`가 통과되었습니다.
- `git diff --check`가 통과되었습니다.
- Claude 6-Tier 검토 아티팩트 `.omx/artifacts/claude-dynamodb-code-review-compact-rerun2-20260523023923.md`가 `P0=0 P1=0`로 종료되었습니다.

## 향후 지침

DynamoDB 그룹 상태가 핫 작동 경로가 되면 필터 표현식을 사용한 전체 테이블 스캔을 피하세요. 쿼리 가능한 `groupPrefix` 속성과 GSI를 추가하거나 그룹 슬롯을 직접 쿼리할 수 있는 테이블 레이아웃을 사용하세요.

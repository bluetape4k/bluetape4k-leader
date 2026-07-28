# 이슈 #419 전략적 선거 예시

## 맥락

루트 README에는 전략적 선택 API가 문서화되어 있지만 예제 카탈로그에는 실행 가능한 백엔드 중립적 가중치 선택 시나리오가 없었습니다.

## 결정

지역 전략 선거 데모로 `examples/strategic-election`를 추가했습니다. 이 예제에서는 새로운 `leader-core` API를 추가하는 대신 `WeightedScorer`, `ScoredElectionStrategy` 및 `LocalStrategicLeaderElector`를 재사용합니다.

## 결과

이 예에서는 가중치가 부여된 상태, 용량, success률 및 유휴 시간 점수를 사용하여 하나의 유지 관리 노드를 선택합니다. 승자가 아닌 노드는 건너뛴 보고서를 반환합니다.

## 검증

- `./gradlew projects`
- `./gradlew :examples:strategic-election:test`
- `./gradlew :examples:strategic-election:run`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml`
- `git diff --check`

## 향후 지침

백엔드 중립적 예제는 설정, 루트 README 로케일 세트, repo-local AGENTS 모듈 목록, CI 경로 필터/작업, 주간 예제 워크플로 및 테스트 리소스 등 전체 모듈 체인을 계속 연결해야 합니다.

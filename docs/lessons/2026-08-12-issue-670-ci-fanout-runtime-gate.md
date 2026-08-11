# 2026-08-12 Issue #670 CI fan-out 및 runtime gate

## 맥락

기존 PR CI는 `leader-core`와 Gradle 설정 변경을 모든 downstream 계약 테스트로 전파하지 않았고, K8s `k8sTest`는 Nightly에만 남아 있었습니다. `ci-status`도 의도된 `skipped`와 누락된 필수 테스트를 구분하지 않았습니다.

## 결정

Gradle 설정·카탈로그·BOM·`leader-core`를 보수적인 `dependency-graph` 경로 집합으로 묶어 모든 계약 테스트와 예제를 fan-out합니다. Spring Boot의 `compileOnly` backend 및 Micrometer 변경은 Spring 통합 테스트를 함께 실행하고, K8s 영향은 PR CI에서 `test`와 `k8sTest`를 모두 실행합니다. `ci-contract` 정적 계약 검사와 `ci-status` runtime 검사는 영향 필터가 참인데도 job이 `skipped`인 경우 실패시키며, 영향이 없는 `skipped`만 `N/A`로 기록합니다.

## 결과

새 계약 스크립트가 모든 테스트 job의 dependency-graph 조건, 필터 출력 선언, Spring Boot downstream 목록, K8s runtime task를 지속적으로 검증합니다. 합성 runtime fixture는 필수 job의 누락과 의도된 N/A skip을 서로 다른 결과로 판정합니다.

## 검증

- `python3 scripts/ci/validate_ci_fanout.py --self-test`
- `python3 scripts/ci/validate_ci_fanout.py --static`
- `actionlint .github/workflows/ci.yml`
- `git diff --check`

## 미래의 규칙

새 publishable module이나 `compileOnly` backend를 추가하면 `dependency-graph` 경로와 downstream job 조건을 함께 갱신하고, 해당 job을 runtime fixture에 등록하세요. `ci-status`에서 `skipped`를 전체 성공으로 취급하지 마세요.

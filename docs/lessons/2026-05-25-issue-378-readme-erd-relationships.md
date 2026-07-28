# 문제 378 README ERD 관계

## 맥락

루트 README에는 모듈, 예제 및 통합 표면에 대한 조밀한 테이블이 있습니다. 문제 378에서는 테이블을 신뢰할 수 있는 소스로 유지하면서 관계를 더 쉽게 검색할 수 있는 ERD 스타일의 시각적 요약을 요청했습니다.

## 결정

예제 테이블 관계를 하나의 공유 README 다이어그램으로 변환합니다.

- 모듈 카탈로그 행은 백엔드 및 통합 모듈을 게시합니다.
- 백엔드 기능 행은 실행 가능한 예시를 제공합니다.
- 통합 표면 행은 Ktor, Spring Boot 또는 Micrometer가 시나리오의 일부인 경우 예제로 구성됩니다.

이는 `docs/images/readme-diagrams/` 아래에 단일 SVG/PNG 쌍을 유지하고 예제 테이블 근처의 `README.md` 및 `README.ko.md` 모두에 동일한 PNG를 포함합니다.

## 결과

`readme-table-relationships-erd-01.svg` 및 일치하는 PNG를 추가했습니다. 다이어그램은 자세한 README 테이블을 대체하지 않고 Redis, SQL, MongoDB, Hazelcast, Kubernetes, Ktor, Spring Boot 및 Micrometer 관계를 요약합니다.

## 검증

- `xmllint --noout docs/images/readme-diagrams/*erd*.svg`
- `git diff --check`
- `rg -n 'erd|ERD|readme-diagrams/.*\.png' README.md README.ko.md`
- 현재 모듈/예제 이름에 대해 `README.md`, `settings.gradle.kts` 및 `examples/`에 대해 다이어그램 레이블을 검증했습니다.
- `rsvg-convert`로 PNG를 렌더링하고 결과를 시각적으로 검사했습니다.
- 작업 트리 감사 아티팩트:
  - `.omx/artifacts/issue-378-audit-readme-diagrams-worktree.log`
  - `.omx/artifacts/issue-378-audit-readme-diagram-quality-worktree.log`

글로벌 감사 스크립트는 여전히 이전의 비ERD 다이어그램에서 관련되지 않은 기존 결과와 함께 종료되지만 새 ERD 자산에는 작업 트리 감사 결과가 없습니다.

## 향후 지침

예제 테이블이 변경되면 다음 세 가지 방향을 검증하여 이 ERD를 업데이트합니다.

1. 모든 다이어그램 예제는 여전히 `examples/` 아래에 존재합니다.
2. 모든 다이어그램 모듈/백엔드 이름은 여전히 모듈 테이블 또는 `settings.gradle.kts`에 나타납니다.
3. 관계 화살표는 모듈 카탈로그에서 기능 또는 통합 표면으로, 그리고 실행 가능한 예제로 단방향으로 유지됩니다.

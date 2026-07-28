# 이슈 #491 예제 시나리오 및 Flow 다이어그램

## 맥락

Issue #491에서는 README 포함을 PNG로 유지하고 일치하는 SVG/DOT/일반/Graphviz 증거를 유지하면서 시나리오 및 Flow 다이어그램을 추가하거나 정규화하여 예제 README 세트를 더 쉽게 스캔할 수 있어야 했습니다.

## 결정

- 시나리오, Flow, DynamoDB 아키텍처 및 DynamoDB 시퀀스 자산 등의 정보 소스로 하나의 생성기 `scripts/generate-example-flow-diagrams.mjs`를 사용합니다.
- `README.md` 및 `README.ko.md`에서 공유 재사용을 위해 생성된 다이어그램 레이블을 영어로 유지합니다.
- 생성된 모든 다이어그램의 범례와 함께 리더/success, 건너뛰기/failure, 경합/릴리스 및 재시도/다음 실행 경로에 의미론적 경로 색상을 사용합니다.
- 관련 없는 기존 다이어그램 변동을 방지하기 위해 쓰기 모드 중에 증거 재생성 스크립트를 통해 새로운 대상 SVG만 처리합니다.

## 결과

- ZooKeeper가 아닌 예제에는 이제 영어 및 한국어 README 파일 모두에 Scenario, Architecture, Flow 및 Sequence 섹션이 있습니다.
- DynamoDB 내보내기에는 이제 산문 전용 문서 대신 전체 다이어그램 세트가 있습니다.
- ZooKeeper Scheduler는 자산 변동 없이 기존 4개 다이어그램 세트를 유지했습니다.
- PR CI가 임시 중앙 스냅샷 메타데이터 403 응답을 노출한 후 Gradle 변경 모듈 캐시 TTL이 0초에서 1일로 완화되었으므로 일반 PR CI는 모든 구성에서 bluetape4k SNAPSHOT 메타데이터의 유효성을 다시 검사하지 않습니다.
- PR CI, 예제 및 Nightly Gradle 호출은 더 이상 `--refresh-dependencies`를 통과하지 않습니다. 또한 예약된 워크플로는 Gradle 종속성 캐시를 활성화하여 1일 모듈 변경 TTL이 효과적일 수 있도록 합니다.
- `leader-ktor` 및 `examples-ktor-app`는 필수 `bluetape4k-ktor-testing` 종속성을 유지하지만 CI, 예제 및 전체 Nightly는 이제 더 무거운 Testcontainers 작업 전에 관련 Ktor 테스트 SNAPSHOT 클래스 경로를 준비하므로 내부 실행자가 Gradle 캐시를 저장하고 재사용할 수 있습니다.

## 검증

- `node scripts/generate-example-flow-diagrams.mjs`
- `node scripts/regenerate-readme-diagram-graphviz-evidence.mjs --check` -> `diagrams=99 failures=0`
- `find docs/images/readme-diagrams -maxdepth 1 -name '*.svg' -print0 | xargs -0 xmllint --noout`
- README 이미지 링크 검증 -> `readmes=36 pngEmbeds=136 svgEmbeds=0 missing=0`
- `rg -n -F '.svg)' examples -g 'README*.md'` -> 일치하는 항목 없음
- `git diff --check`
- `./gradlew help --no-daemon`
- `actionlint .github/workflows/ci.yml .github/workflows/examples.yml .github/workflows/nightly-tests.yml`
- `rg -n -- '--refresh-dependencies' .github/workflows/ci.yml .github/workflows/examples.yml .github/workflows/nightly-tests.yml` -> 일치하는 항목 없음
- `rg -n 'cache-disabled: true' .github/workflows/nightly-tests.yml` -> 일치하는 항목 없음
- `./gradlew :bluetape4k-leader-ktor:compileTestKotlin --no-configuration-cache --no-daemon`
- `./gradlew :examples:ktor-app:compileTestKotlin --no-configuration-cache --no-daemon`
- `gh run view 27048770109 --job 79840140153 --log-failed`는 나머지 오류가 테스트 어설션 오류가 아닌 `bluetape4k-ktor-testing` 메타데이터 403임을 검증했습니다.
- `.omx/artifacts/issue-491-example-scenario-flow-contact-sheet.png`를 통한 시각적 QA와 개별 시나리오/DynamoDB PNG 검사.
- 캐시 TTL 변경 후 PR CI가 다시 실행됩니다.

## 향후 지침

- 생성된 README 다이어그램을 직접 편집하지 마십시오. 생성기를 변경하고 영향을 받은 대상만 다시 렌더링한 다음 전역 증거 검증을 실행합니다.
- 쓰기 모드 증거 실행이 관련되지 않은 기존 자산에 영향을 미치는 경우 PR 생성 전에 해당 이탈을 복원하고 `--check`를 다시 실행하십시오.
- Gradle 기본 스타일 변경 모듈 캐시 TTL에서 일반 PR CI, 예제 및 Nightly를 유지합니다. 0초 SNAPSHOT 메타데이터 새로 고침은 매트릭스 작업 전반에 걸쳐 일시적인 중앙 스냅샷 403 오류를 증폭시키므로 명시적인 게시 후 새로 고침 워크플로에만 `--refresh-dependencies`를 사용하세요.
- 필요한 외부 테스트 SNAPSHOT 아티팩트의 경우 관련 테스트 클래스 경로를 검증/컴파일하고 더 무거운 Testcontainers 작업이 실행되기 전에 동일한 저장소 PR 또는 예약된 워크플로 작업에 대해 Gradle 캐시를 쓰는 제한된 준비 작업을 추가합니다.

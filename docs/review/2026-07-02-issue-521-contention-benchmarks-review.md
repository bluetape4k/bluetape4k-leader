# 이슈 #521 경합 벤치마크 검토

## 범위

- `benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/LeaderContentionElectorBenchmark.kt`
- `benchmark/README.md`
- `benchmark/README.ko.md`
- `docs/benchmarks/2026-07-02-issue-521-contention-*`
- `docs/images/readme-charts/leader-contention-*`
- `docs/lessons/2026-07-02-issue-521-contention-benchmarks.md`

## 리뷰 노트

- 직접 비교 검토에서 P0/P1 결과를 찾을 수 없습니다.
- CodeGraph 검토 컨텍스트는 이 작업 트리에 대해 빈 그래프를 반환했으므로 대신 사용된 직접 소스 검사, 패턴 검색, 컴파일, JMH jar 생성 및 smoke 벤치마크 실행을 검토합니다.
- 보류 잠금 설정은 벤치마크 측정 전에 건너뛴 결과와 제로 작업 실행을 검증합니다.
- 로컬 경합은 원격/공유 팩토리에서 분리되므로 프로세스 내 잠금 상태는 벤치마킹된 로컬 선택기 인스턴스에서 공유됩니다.
- 의미 있는 경합이 측정되기 전에 `withTimeoutOrNull(0)`가 시간 초과될 수 있으므로 로컬 일시 중단 `waitTime=0` 병렬 행은 의도적으로 없습니다.
- `runCatching`는 일시 중단되지 않은 리소스 정리 도우미에만 나타납니다.

## 검증 증거

- `:benchmark:compileBenchmarkKotlin`가 통과되었습니다.
- `:benchmark:benchmarkBenchmarkJar`가 통과되었습니다.
- 로컬 전용 JMH 연기가 `contenders=2`와 함께 전달되었습니다.
- Lettuce 컨테이너 지원 건너뛰기 경로 JMH 연기가 `contenders=2`와 함께 전달되었습니다.
- 4개의 Issue #521 원시 JSON 파일 모두에 대해 `jq empty`가 통과되었습니다.
- 두 차트 SVG 파일 모두에 대해 `xmllint --noout`가 전달되었습니다.
- CairoSVG는 차트 PNG 파일을 모두 렌더링했으며 육안 검사에서 레이블, 범례 또는 축 중복이 발견되지 않았습니다.
- `node scripts/check-readme-language-switches.mjs`가 통과되었습니다.
- `git diff --check`가 통과되었습니다.

## 잔여 위험

- README 차트는 빠른 준비 작업 없음, 포크 없음, 100ms JMH 스냅샷을 사용합니다. 이는 릴리스 등급 순위가 아닌 회귀 및 백엔드 형태의 증거입니다.
- Exposed H2 경합 행은 구현되었지만 원격 백엔드와 로컬 기준 행에 차트 초점을 유지하기 위해 README 차트 스냅샷에 포함되지 않았습니다.

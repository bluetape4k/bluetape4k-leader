# 검토 - Issue #523 기록 레코더 관찰 가능성 벤치마크

범위:

- `benchmark/src/benchmark/kotlin/io/bluetape4k/leader/benchmark/HistoryRecorderBenchmark.kt`
- `benchmark/build.gradle.kts`
- `benchmark/README.md`
- `benchmark/README.ko.md`
- `docs/benchmarks/2026-07-02-issue-523-history-recorder-observability.md`
- `docs/images/readme-charts/leader-history-observability-*-chart-01.{svg,png}`

## 조사 결과

P0/P1 발견 항목이 없습니다.

## 리뷰 노트

- 벤치마크 종속성 변경은 게시되지 않은 `benchmark` 소스 세트로 범위가 지정되며 새 Micrometer 레코더 행이 컴파일되는 위치에만 `:bluetape4k-leader-micrometer`를 추가합니다.
- JMH 매개변수 매트릭스는 벤치마크 메소드 이름을 통한 레코더 유형, 메소드 이름을 통한 터미널 상태, `metadataMode=empty|small|large`를 통한 메타데이터 크기를 다룹니다.
- 벤치마크는 기록 전용으로 유지됩니다. 백엔드 잠금, Spring 조언, 외부 메트릭 백엔드, 내보내기, 스크랩 또는 푸시 레지스트리 오버헤드를 요구하지 않습니다.
- 현재 기록 레코더 API에는 건너뛰거나 선택하지 않은 터미널 이벤트가 없습니다. 문서는 비계약 행을 만드는 대신 건너뛴 동작을 Issue #521 경합 벤치마크로 명시적으로 라우팅합니다.
- 차트 QA: SVG XML 구문 분석, CairoSVG에서 두 PNG 모두 렌더링, 제목과 바닥글 겹침을 수정한 후 두 개의 전체 크기 PNG를 육안으로 검사했습니다.

## 7층 관문

| Tier | Verdict | Evidence |
|---|---|---|
| Tier 1 Security | PASS | No production input/output boundary changed; token is still consumed only by existing history record model. |
| Tier 2 Architecture | PASS | New coverage stays in existing benchmark module; no production module dependency or runtime path changed. |
| Tier 3 Data/State | PASS | In-memory benchmark sink now preserves completed and failed terminal states for equivalent sink work. |
| Tier 4 Correctness | PASS | Compile and JMH runs generated 36 rows for throughput and 36 rows for average time. |
| Tier 5 Test/Benchmark | PASS | `compileBenchmarkKotlin`, `benchmarkBenchmarkJar`, filtered JMH throughput, and filtered JMH average-time evidence recorded. |
| Tier 6 Performance | PASS | Results are documented as same-machine snapshots; metadata-size and failure-path costs are called out. |
| Tier 7 Docs/Release | PASS | README and README.ko updated together with raw JSON, charts, command, run conditions, table, and interpretation. |

P0: 0 P1: 0

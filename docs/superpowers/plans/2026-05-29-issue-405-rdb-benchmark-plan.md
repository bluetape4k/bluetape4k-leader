# Issue 405 RDB Benchmark Plan

## 한국어 해설

이 문서는 `Issue 405 RDB Benchmark Plan`에 대한 설계 또는 실행 계획 기록입니다. 아래 원문 구조의 범위, 결정, 작업 순서, 검증 조건, 위험 및 후속 조치는 기록 보존을 위해 유지합니다. 검토자는 각 `Action`, `Expected DoD`, `Validation`, `Target files` 항목을 한국어 해설과 함께 읽고, 코드 식별자와 명령은 원문 그대로 취급해야 합니다.



## Steps

1. Add benchmark dependencies for PostgreSQL/MySQL JDBC, R2DBC, and
   Testcontainers.
2. Extend blocking benchmark params with `exposed-jdbc-postgresql` and
   `exposed-jdbc-mysql`.
3. Extend suspend benchmark params with `exposed-r2dbc-postgresql` and
   `exposed-r2dbc-mysql`.
4. Run compile-only verification first.
5. Run the full default benchmark target sequentially and store raw JSON.
6. Update README tables and regenerate distributed backend charts as SVG and
   PNG.
7. Validate chart XML, PNG renderability, Gradle compile, and Markdown diff.

## Stop Condition

The work is done when compile and full benchmark run pass, README tables and
charts show PostgreSQL/MySQL rows, and generated chart images have been visually
inspected.

# 문제 405 RDB 벤치마크

## 맥락

벤치마크 문서에는 H2 SQL/R2DBC 행이 있었지만 분산 SQL 백엔드 행은 없었습니다. H2가 인프라 지원 조정이 아닌 로컬 모양 오버헤드를 측정하기 때문에 Exposed 백엔드 그림이 오해를 불러일으킵니다.

## 결정

Exposed JDBC를 차단하고 Exposed R2DBC를 일시 중단하기 위한 PostgreSQL 및 MySQL 벤치마크 매개변수를 추가합니다. H2를 로컬 형태 검사로 처리하고 공개 차트의 이름을 분산 백엔드로 바꿉니다.

## 결과

이제 전체 기본 벤치마크에는 처리량 및 평균 실행 시간 모두에 PostgreSQL 및 MySQL 행이 포함됩니다. 원시 JSON은 2026년 5월 29일 실행을 위해 커밋되고 README 테이블/차트는 해당 데이터를 사용합니다.

## 검증

- `./gradlew :benchmark:compileBenchmarkKotlin --no-daemon`
- `./gradlew :benchmark:benchmarkBenchmark :benchmark:benchmarkAverageTimeBenchmark --no-configuration-cache --rerun-tasks --no-daemon`
- `xmllint --noout docs/images/readme-charts/leader-benchmark-distributed-*.svg`
- PNG 차트 육안 검사

## 향후 지침

분산 백엔드 차트에서 로컬/H2 행을 유지하세요. JVM-로컬 조정의 경우 H2 리더 선택 대신 로컬 잠금 기본 형식을 선호합니다.

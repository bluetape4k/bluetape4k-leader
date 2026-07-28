# 이슈 520 구현 검토

## 범위

단계적 Issue #520 변경 사항을 검토했습니다.

- `LeaderGroupElectorBenchmark` 및 `SuspendLeaderGroupElectorBenchmark`
- README 및 README.ko 벤치마크 업데이트
- 원시 JMH JSON, 벤치마크 보고서, 차트 SVG/PNG 자산 및 강의 노트

## 조사 결과

- P0: 없음.
- P1: 없음.

## 메모

- 초기 일시 중지 그룹 벤치마크에는 `exposed-r2dbc-h2`가 포함되었지만 생성된 JMH jar는 현재 잘못된 `META-INF/services/io.r2dbc.spi.ConnectionFactoryProvider` 리소스를 노출하므로 R2DBC H2 설정에서 JMH 행이 0개 생성됩니다. 이슈 #520 벤치마크는 이제 failure한 일시 중지 H2 매개변수를 제외하고 일시 중지 그룹 행에 대해 로컬, Redis, MongoDB 및 ZooKeeper만 문서화합니다. JDBC H2 차단은 계속해서 다루어집니다.
- 슬롯 홀더는 동시성 정확성 테스트가 아니라 벤치마크 고정 장치이기 때문에 `MultithreadingTester`는 여기에 적용할 수 없습니다. 벤치마크는 실제 선택기 API를 사용하고 연기 `runIfLeaderResult` 검사를 통해 설정을 검증합니다.

## 검증 증거

- `./gradlew :benchmark:compileBenchmarkKotlin --no-configuration-cache --console=plain --warning-mode all`: 통과
- `./gradlew :benchmark:benchmarkBenchmarkJar --no-daemon --no-configuration-cache --console=plain --rerun-tasks`: 통과
- 두 그룹 벤치마크 클래스 모두에 대한 분기된 로컬 JMH 기준: 10행
- 최신 차트 범위 처리량 스냅샷 검증: 33행
- 최신 차트 범위 평균 시간 스냅샷 검증: 33행
- 새로운 차트 SVG 파일의 `xmllint --noout`: PASS
- 원시 JSON 파일의 `jq empty`: PASS
- `git diff --cached --check`: 통과
